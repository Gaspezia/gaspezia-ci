// Pipeline CD complet des API Node/NestJS Gaspezia (agent k8s + Kaniko + bump gaspezia-stacks
// + notification Discord). Remplace les ~295 lignes de Jenkinsfile dupliquees a l'identique
// dans bot-twitch-api, mon-labo-gourmand-api, gaspezia-members-api, email-sender-api...
//
// POURQUOI une bibliotheque : ces 4 Jenkinsfile ne divergeaient que par le nom d'image et
// quelques ressources, mais chaque correctif (OOMKill du conteneur `node` le 2026-08-05,
// conteneur kaniko-migrate separe, retrait du cache Kaniko mesure sans effet le 2026-07-23)
// devait etre reporte a la main dans chacun -> derive garantie. Ici : un seul endroit.
//
// Usage (chargement DYNAMIQUE : aucune config Jenkins globale, cf. gaspeziaNodeQuality) :
//   library identifier: 'gaspezia-ci@main', retriever: modernSCM([$class: 'GitSCMSource',
//       remote: 'https://github.com/Gaspezia/gaspezia-ci.git', credentialsId: 'github-gaspezia-stacks'])
//   gaspeziaNodeApi(imageName: 'bot-twitch-api')
//
// Contrat de comportement (a NE PAS casser — c'est ce que la prod consomme) :
//   - PR              -> Quality + compile check `--target pr --no-push`. AUCUN push, AUCUN bump.
//   - branche dev     -> pousse <img>:dev-<sha> + <img>:dev (et -migrate), bump du kustomization STAGING.
//   - branche main    -> pousse <img>:main-<sha> + <img>:main (et -migrate), AUCUN bump (main n'est pas deploye).
//   - tag vX.Y.Z      -> pousse <img>:vX.Y.Z (SANS tag mobile), bump du kustomization PROD.
//   - toujours        -> notification Discord native (curl), puis cleanWs().
def call(Map config = [:]) {

    // ---- Parametres ---------------------------------------------------------------------
    // imageName est le seul obligatoire : il derive le nom d'image, les labels et, par
    // convention, les chemins kustomization dans gaspezia-stacks.
    String imageName = config.imageName
    if (!imageName) {
        error 'gaspeziaNodeApi: parametre `imageName` obligatoire (ex: imageName: "bot-twitch-api").'
    }

    String registry       = config.registry            ?: 'nexus.gaspezia.lan/docker-private'
    // Les chemins sont surchargeables : email-sender-api s'appelle `gaspezia-email-sender-api`
    // cote image mais `email-sender-api` cote gaspezia-stacks — la convention ne suffit pas.
    String stacksProd     = config.stacksProdKustomization    ?: "k8s/${imageName}/base/kustomization.yaml"
    String stacksStaging  = config.stacksStagingKustomization ?: "k8s/${imageName}-staging/base/kustomization.yaml"
    String gitCredsId     = config.gitCredentialsId     ?: 'github-gaspezia-stacks'
    String discordCredsId = config.discordCredentialsId ?: 'discord-webhook'
    String partOf         = config.partOf              ?: imageName
    String nodeImage      = config.nodeImage           ?: 'node:22-bookworm'
    // email-sender-api n'a pas de gate pre-migration (pas de Prisma) : pas d'image -migrate,
    // et donc pas de conteneur kaniko-migrate a provisionner pour rien.
    boolean buildMigrate  = config.containsKey('buildMigrateImage') ? config.buildMigrateImage : true

    // Ressources : `requests` = ce qui pese sur l'ordonnancement k8s, `limits` = plafond qui
    // ne reserve rien. D'ou des requests modestes et des limits hautes.
    // NB: pas d'operateur `<<` ni de `+` sur Map ici — le bac a sable Groovy de Jenkins les
    // rejette selon la version du plugin script-security. Un `?:` par cle, explicite et sur.
    Map r = (config.resources ?: [:])
    Map res = [
        jnlpCpuLimit  : r.jnlpCpuLimit  ?: '2',
        nodeCpuLimit  : r.nodeCpuLimit  ?: '3',
        // 3Gi et non 2Gi : mesure Prometheus sur 7 j, pic reel du conteneur `node` a 2043 Mi
        // pour une limite de 2048 Mi — il touchait le plafond, OOMKill le 2026-08-05.
        nodeMemLimit  : r.nodeMemLimit  ?: '3Gi',
        sonarCpuLimit : r.sonarCpuLimit ?: '3',
        sonarMemLimit : r.sonarMemLimit ?: '2Gi',
        kanikoCpuLimit: r.kanikoCpuLimit?: '4',
        // Le conteneur kaniko-migrate a ses PROPRES plafonds : gaspezia-minecraft-api le
        // limite a 1 CPU / 2Gi la ou son kaniko principal est a 2 CPU / 4Gi. Sans ces deux
        // cles, la bibliotheque ne savait pas exprimer ce depot et sa migration n'aurait
        // pas ete un non-evenement (elle lui aurait remonte les plafonds en douce).
        // Par defaut on retombe sur kanikoCpuLimit : c'est ce que faisait le code d'origine.
        kanikoMigrateCpuLimit: r.kanikoMigrateCpuLimit ?: r.kanikoCpuLimit ?: '4',
        kanikoMigrateMemLimit: r.kanikoMigrateMemLimit ?: '3Gi',
    ]

    // ---- Pod agent ----------------------------------------------------------------------
    // Deux conteneurs Kaniko et non un seul : un `/kaniko/executor` DETRUIT le rootfs du
    // conteneur en fin de run, on ne peut donc pas enchainer deux builds dans le meme
    // conteneur (le 2e `sh` n'a plus de /busybox/sh).
    String migrateContainer = !buildMigrate ? '' : """
    - name: kaniko-migrate
      image: gcr.io/kaniko-project/executor:debug
      command: ["/busybox/cat"]
      tty: true
      resources:
        requests: { cpu: "150m", memory: "768Mi", ephemeral-storage: "4Gi" }
        limits:   { cpu: "${res.kanikoMigrateCpuLimit}", memory: "${res.kanikoMigrateMemLimit}", ephemeral-storage: "8Gi" }
      volumeMounts:
        - name: nexus-docker-config
          mountPath: /kaniko/.docker
        - name: gaspezia-ca
          mountPath: /kaniko/ssl/certs/additional-ca-cert-bundle.crt
          subPath: ca.crt
        - name: nexus-npmrc
          mountPath: /kaniko/nexus-npmrc"""

    String podYaml = """
apiVersion: v1
kind: Pod
metadata:
  labels:
    app.kubernetes.io/component: jenkins-build
    app.kubernetes.io/part-of: ${partOf}
spec:
  serviceAccountName: jenkins-agent
  containers:
    - name: jnlp
      image: nexus.gaspezia.lan/docker-private/jenkins-inbound-agent:gaspezia
      resources:
        requests: { cpu: "100m", memory: "256Mi" }
        limits:   { cpu: "${res.jnlpCpuLimit}", memory: "512Mi" }
    - name: kaniko
      image: gcr.io/kaniko-project/executor:debug
      command: ["/busybox/cat"]
      tty: true
      resources:
        requests: { cpu: "250m", memory: "1536Mi", ephemeral-storage: "6Gi" }
        limits:   { cpu: "${res.kanikoCpuLimit}", memory: "4Gi", ephemeral-storage: "12Gi" }
      volumeMounts:
        - name: nexus-docker-config
          mountPath: /kaniko/.docker
        - name: gaspezia-ca
          mountPath: /kaniko/ssl/certs/additional-ca-cert-bundle.crt
          subPath: ca.crt
        - name: nexus-npmrc
          mountPath: /kaniko/nexus-npmrc${migrateContainer}
    - name: node
      image: ${nodeImage}
      command: ["cat"]
      tty: true
      # C'est ce conteneur qui execute `pnpm install --frozen-lockfile` a
      # l'etape Lint & Test (gaspeziaNodeQuality). Pour un depot qui declare une
      # dependance @gaspezia/*, il lui faut la CA interne ET l'identifiant de
      # lecture, sans quoi l'install echoue en UNABLE_TO_VERIFY_LEAF_SIGNATURE
      # puis en 401 — et comme l'etape est enveloppee dans un catchError, elle
      # ne rougit pas le build : elle le rend UNSTABLE. On lit les logs.
      #  * NODE_EXTRA_CA_CERTS AJOUTE la CA Gaspezia aux racines compilees dans
      #    Node. update-ca-certificates ne suffirait pas : il alimente le
      #    magasin d'OpenSSL, pas celui de Node, et c'est Node que pnpm utilise.
      #    A ne surtout pas remplacer par `cafile=` dans un .npmrc : cafile
      #    REMPLACE le magasin entier et casserait tout tirage depuis npmjs.
      #  * NPM_CONFIG_USERCONFIG remplace le .npmrc UTILISATEUR (~/.npmrc, qui
      #    n'existe pas dans l'image node officielle : rien n'est donc perdu).
      #    Il ne remplace PAS le .npmrc du DEPOT, qui porte la redirection de
      #    scope @gaspezia et continue d'etre lu — verifie avec pnpm 11.
      # Secret absent => le fichier n'existe pas => pnpm l'ignore sans erreur
      # ni avertissement. Les quatre autres API ne voient aucune difference.
      env:
        - name: NODE_EXTRA_CA_CERTS
          value: /etc/gaspezia/ca/ca.crt
        - name: NPM_CONFIG_USERCONFIG
          value: /etc/gaspezia/npmrc/.npmrc
      resources:
        requests: { cpu: "200m", memory: "768Mi" }
        limits:   { cpu: "${res.nodeCpuLimit}", memory: "${res.nodeMemLimit}" }
      volumeMounts:
        - name: gaspezia-ca
          mountPath: /etc/gaspezia/ca
        - name: nexus-npmrc
          mountPath: /etc/gaspezia/npmrc
    - name: sonar-scanner
      image: sonarsource/sonar-scanner-cli:latest
      command: ["cat"]
      tty: true
      resources:
        requests: { cpu: "150m", memory: "768Mi" }
        limits:   { cpu: "${res.sonarCpuLimit}", memory: "${res.sonarMemLimit}" }
  volumes:
    - name: nexus-docker-config
      secret:
        secretName: nexus-ci
        items:
          - { key: .dockerconfigjson, path: config.json }
    - name: gaspezia-ca
      configMap:
        name: gaspezia-ca
        items:
          - { key: ca.crt, path: ca.crt }
    # Fragment de .npmrc donnant l'acces en LECTURE au depot npm-private du
    # Nexus. Consomme par le seul depot qui tire des paquets @gaspezia/*.
    #
    # A CREER A LA MAIN dans le namespace des agents (jenkins-builds) :
    #   kubectl -n jenkins-builds create secret generic nexus-npmrc --from-file=.npmrc=./fragment.npmrc
    #
    # Contenu attendu de ./fragment.npmrc : UNE ligne, cle SCOPEE au registre,
    # dans l'une des deux formes acceptees par Nexus —
    #   //nexus.gaspezia.lan/repository/npm-private/:_authToken=NpmToken.xxxxx
    #   //nexus.gaspezia.lan/repository/npm-private/:_auth=<base64 user:motdepasse>
    #
    # La cle du Secret DOIT s'appeler .npmrc : c'est ce nom que le conteneur
    # node lit via NPM_CONFIG_USERCONFIG, et que les Dockerfiles bot-twitch
    # cherchent en premier dans le repertoire monte sur /kaniko/nexus-npmrc.
    #
    # NE JAMAIS ecrire la cle sans son prefixe de registre (`_authToken=...`
    # tout court) : pnpm la rattache alors d'office a registry.npmjs.org, et le
    # jeton Nexus partirait vers le registre PUBLIC — depuis les cinq pipelines.
    #
    # `optional: true` n'est pas une precaution de style : le Secret n'existe
    # pas encore, et quatre des cinq API n'en auront jamais besoin. Un volume de
    # Secret non optionnel bloquerait leurs pods en ContainerCreating. Meme
    # raison pour l'absence de `subPath` cote montage (contrairement au style
    # employe plus haut pour gaspezia-ca, configMap REQUIS et toujours present) :
    # un subPath sur un Secret optionnel ABSENT fait echouer le montage, car il
    # reference un fichier qui n'existe pas. Monte en repertoire, un Secret
    # absent donne simplement un repertoire vide.
    - name: nexus-npmrc
      secret:
        secretName: nexus-npmrc
        optional: true
"""

    pipeline {
        agent {
            kubernetes {
                cloud 'k8s'
                defaultContainer 'jnlp'
                yaml podYaml
            }
        }

        options {
            disableConcurrentBuilds()
            skipDefaultCheckout(true)
        }

        environment {
            DOCKER_REGISTRY_PRIVATE = "${registry}"
            IMAGE_NAME              = "${imageName}"
        }

        stages {

            stage('Checkout') {
                steps { checkout scm }
            }

            stage('Quality (CI)') {
                when { anyOf { changeRequest(); branch 'dev'; branch 'main' } }
                steps {
                    script { gaspeziaNodeQuality() }
                }
            }

            stage('CI - PR Compile Check') {
                when { expression { return env.CHANGE_ID != null } }
                steps {
                    script { env.PIPELINE_MODE = 'pr' }
                    container('kaniko') {
                        sh '''
                          /kaniko/executor \
                            --context dir://$(pwd) \
                            --dockerfile Dockerfile \
                            --target pr \
                            --no-push
                        '''
                    }
                }
            }

            stage('Compute Build Context') {
                when { expression { return env.CHANGE_ID == null } }
                steps {
                    script {
                        def reg = env.DOCKER_REGISTRY_PRIVATE

                        if (env.BRANCH_NAME == 'dev') {
                            def shortCommit         = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
                            env.PIPELINE_MODE       = 'dev'
                            env.DOCKER_TAG          = "dev-${shortCommit}"
                            env.DOCKER_TAG_STABLE   = 'dev'
                            env.DOCKER_IMAGE        = "${reg}/${imageName}:${env.DOCKER_TAG}"
                            env.DOCKER_IMAGE_STABLE = "${reg}/${imageName}:${env.DOCKER_TAG_STABLE}"

                        } else if (env.BRANCH_NAME == 'main') {
                            def shortCommit         = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
                            env.PIPELINE_MODE       = 'main'
                            env.DOCKER_TAG          = "main-${shortCommit}"
                            env.DOCKER_TAG_STABLE   = 'main'
                            env.DOCKER_IMAGE        = "${reg}/${imageName}:${env.DOCKER_TAG}"
                            env.DOCKER_IMAGE_STABLE = "${reg}/${imageName}:${env.DOCKER_TAG_STABLE}"

                        } else if (env.TAG_NAME) {
                            // Release : tag immuable UNIQUEMENT (pas de tag mobile), c'est la
                            // convention CD GitOps — un manifeste ne doit jamais pointer un tag mobile.
                            env.PIPELINE_MODE       = 'release'
                            env.GIT_TAG             = env.TAG_NAME
                            env.DOCKER_TAG          = env.TAG_NAME
                            env.DOCKER_TAG_STABLE   = ''
                            env.DOCKER_IMAGE        = "${reg}/${imageName}:${env.DOCKER_TAG}"

                        } else {
                            env.PIPELINE_MODE       = 'none'
                            echo "Branch ${env.BRANCH_NAME} is not handled by CD."
                        }

                        echo "PIPELINE_MODE = ${env.PIPELINE_MODE}"
                        echo "DOCKER_IMAGE  = ${env.DOCKER_IMAGE ?: 'N/A'}"
                    }
                }
            }

            stage('Build & Push (Kaniko)') {
                when {
                    allOf {
                        expression { return env.CHANGE_ID == null }
                        expression { return env.PIPELINE_MODE in ['dev', 'main', 'release'] }
                    }
                }
                steps {
                    container('kaniko') {
                        sh '''
                          set -e
                          DEST="--destination ${DOCKER_REGISTRY_PRIVATE}/${IMAGE_NAME}:${DOCKER_TAG}"
                          if [ -n "${DOCKER_TAG_STABLE}" ]; then
                            DEST="$DEST --destination ${DOCKER_REGISTRY_PRIVATE}/${IMAGE_NAME}:${DOCKER_TAG_STABLE}"
                          fi
                          /kaniko/executor \
                            --context dir://$(pwd) \
                            --dockerfile Dockerfile \
                            --target runtime \
                            --skip-unused-stages=true \
                            ${DEST}
                        '''
                    }
                    // Image du gate pre-migration (Job ArgoCD PreSync). Conteneur kaniko
                    // SEPARE (un executor detruit le rootfs). Nom ...-migrate, meme tag.
                    script {
                        if (buildMigrate) {
                            container('kaniko-migrate') {
                                sh '''
                                  set -e
                                  DEST_MIGRATE="--destination ${DOCKER_REGISTRY_PRIVATE}/${IMAGE_NAME}-migrate:${DOCKER_TAG}"
                                  if [ -n "${DOCKER_TAG_STABLE}" ]; then
                                    DEST_MIGRATE="$DEST_MIGRATE --destination ${DOCKER_REGISTRY_PRIVATE}/${IMAGE_NAME}-migrate:${DOCKER_TAG_STABLE}"
                                  fi
                                  /kaniko/executor \
                                    --context dir://$(pwd) \
                                    --dockerfile Dockerfile \
                                    --target migrate \
                                    --skip-unused-stages=true \
                                    ${DEST_MIGRATE}
                                '''
                            }
                        }
                    }
                }
            }

            stage('Update gaspezia-stacks Repo') {
                when { expression { return env.PIPELINE_MODE in ['dev', 'release'] } }
                steps {
                    script {
                        def targetFile = (env.PIPELINE_MODE == 'release') ? stacksProd : stacksStaging
                        def newTag     = (env.PIPELINE_MODE == 'release') ? env.GIT_TAG : env.DOCKER_TAG
                        withCredentials([usernamePassword(
                            credentialsId: gitCredsId,
                            usernameVariable: 'GIT_USERNAME',
                            passwordVariable: 'GIT_TOKEN'
                        )]) {
                            sh """
                              set -e
                              rm -rf gaspezia-stacks
                              git clone https://\${GIT_USERNAME}:\${GIT_TOKEN}@github.com/Gaspezia/gaspezia-stacks.git
                              cd gaspezia-stacks

                              if [ ! -f "${targetFile}" ]; then
                                echo "Skipping: ${targetFile} does not exist"
                                exit 0
                              fi

                              sed -i "s|newTag: .*|newTag: ${newTag}|" "${targetFile}"

                              git config user.name "Jenkins"
                              git config user.email "jenkins@gaspezia.lan"
                              git add ${targetFile}

                              if git diff --cached --quiet; then
                                echo "No change detected in ${targetFile}"
                              else
                                git commit -m "chore(${imageName}): bump image to ${newTag} (${env.PIPELINE_MODE})"
                                git push origin main
                              fi
                            """
                        }
                    }
                }
            }
        }

        post {
            always {
                script {
                    def branchRef = env.TAG_NAME ?: env.BRANCH_NAME
                    def lines = ["**Branch:** ${branchRef}"]

                    if (env.CHANGE_ID)          { lines << "**PR:** #${env.CHANGE_ID}" }
                    if (env.DOCKER_IMAGE)       { lines << "**Image:** ${env.DOCKER_IMAGE}" }
                    if (env.DOCKER_IMAGE_STABLE){ lines << "**Stable:** ${env.DOCKER_IMAGE_STABLE}" }
                    if (env.PIPELINE_MODE == 'release' && env.GIT_TAG) { lines << "**Tag:** ${env.GIT_TAG}" }

                    def result = currentBuild.currentResult
                    def icon  = ['SUCCESS': '✅', 'FAILURE': '❌', 'UNSTABLE': '⚠️'].get(result, 'ℹ️')
                    def color = ['SUCCESS': 3066993, 'FAILURE': 15158332, 'UNSTABLE': 16776960].get(result, 9807270)

                    // Notification Discord en NATIF (POST webhook via curl) — pas de
                    // dependance au plugin Jenkins Discord (discordSend).
                    def payload = groovy.json.JsonOutput.toJson([
                        username: 'Jenkins',
                        embeds: [[
                            title      : "${icon} ${imageName} — ${result}",
                            description: lines.join('\n'),
                            url        : env.BUILD_URL,
                            color      : color,
                            footer     : [text: 'Pipeline Notification']
                        ]]
                    ])
                    writeFile file: 'discord-payload.json', text: payload

                    withCredentials([string(credentialsId: discordCredsId, variable: 'DISCORD_WEBHOOK')]) {
                        sh '''
                          set +x
                          curl -sf -H "Content-Type: application/json" \
                               -X POST --data @discord-payload.json "$DISCORD_WEBHOOK" \
                            || echo "Discord notify failed (non-bloquant)"
                        '''
                    }
                    cleanWs()
                }
            }
        }
    }
}
