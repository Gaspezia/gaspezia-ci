// Pipeline complet des fronts Angular Gaspezia (build > pr > runtime).
//
// POURQUOI : les 7 fronts Angular portaient le MEME Jenkinsfile de ~270 lignes,
// recopie a la main. Chaque correctif devait donc etre reapplique 7 fois, et il
// ne l'etait jamais entierement : au 2026-08-05, la limite CPU de `jnlp` valait
// 500m ici et 2 ailleurs, et bot-discord-web poussait dans gaspezia-stacks le
// tag MOBILE (`dev`) la ou les autres poussent le tag IMMUABLE (`dev-<sha>`),
// ce qui casse silencieusement la tracabilite GitOps. Un seul endroit a
// corriger = plus de derive possible.
//
// Le Jenkinsfile du depot consommateur se reduit a :
//
//   library identifier: 'gaspezia-ci@main', retriever: modernSCM([$class: 'GitSCMSource',
//       remote: 'https://github.com/Gaspezia/gaspezia-ci.git', credentialsId: 'github-gaspezia-stacks'])
//   gaspeziaAngularWeb(imageName: 'dorangeontraiteur-web', devConfiguration: 'staging')
//
// Pipeline SCRIPTE et non declaratif : le `agent { kubernetes { yaml ... } }`
// d'un pipeline declaratif ne peut pas venir d'une bibliotheque (il est evalue
// avant tout `script {}`). Le podTemplate scripte, lui, s'instancie depuis le
// code de la bibliotheque -- c'est la seule facon de sortir AUSSI le pod du
// Jenkinsfile. Consequence visible dans les logs : la bibliotheque est chargee
// une fois au tout debut (et non dans le stage 'Quality (CI)'), et les stages
// non applicables affichent un message de saut au lieu du "skipped due to when
// conditional" du declaratif. Aucun effet fonctionnel.
//
// Pre-requis depot : Dockerfile a 3 etages (build > pr > runtime), fichier
// sonar-project.properties, et les credentials Jenkins github-gaspezia-stacks,
// discord-webhook, sonarqube-token.
//
// Acces au Nexus npm : le pod monte, EN OPTIONNEL, le Secret `nexus-npmrc` du
// namespace `jenkins-builds` (fragment d'authentification au depot npm-private).
// Rien a declarer cote depot : un front qui ne consomme aucun paquet
// `@gaspezia/*` ne le lit jamais, et un Secret absent donne un repertoire vide
// sans bloquer le pod. Detail et preuve d'inertie au bloc `volumes` ci-dessous.
//
// Parametres (tous optionnels sauf imageName) :
//   imageName          (REQUIS) nom de l'image ET racine des chemins gaspezia-stacks
//   partOf             label app.kubernetes.io/part-of du pod        (defaut: imageName)
//   registry           registre Docker prive                          (defaut: nexus.gaspezia.lan/docker-private)
//   dockerfile         chemin du Dockerfile                           (defaut: Dockerfile)
//   prTarget           etage Docker verifie en PR                     (defaut: pr)
//   runtimeTarget      etage Docker publie                            (defaut: runtime)
//   devConfiguration   `--build-arg configuration=` sur dev           (defaut: staging)
//   prodConfiguration  idem sur main et sur les tags                  (defaut: production)
//                      -> passer '' pour ne PAS emettre le build-arg (front sans configuration)
//   buildArgs          Map d'ARG supplementaires passes a kaniko      (defaut: [:])
//   stacksProdKustomization     (defaut: k8s/<imageName>/base/kustomization.yaml)
//   stacksStagingKustomization  (defaut: k8s/<imageName>-staging/base/kustomization.yaml)
//   resources          Map de limits surchargeables : jnlpCpuLimit (2),
//                      kanikoCpuLimit (2), nodeCpuLimit (3), nodeMemLimit (3Gi),
//                      sonarCpuLimit (3), sonarMemLimit (2Gi). Les `requests`
//                      ne sont PAS surchargeables : elles seules pesent sur
//                      l'ordonnancement et sont deja harmonisees sur tout le parc.
//   sonarBranch        branche analysee par Sonar (CE = mono-branche) (defaut: dev)
def call(Map config = [:]) {

    if (!config.imageName) {
        error('gaspeziaAngularWeb: parametre `imageName` obligatoire.')
    }

    String imageName         = config.imageName
    String partOf            = config.partOf            ?: imageName
    String registry          = config.registry          ?: 'nexus.gaspezia.lan/docker-private'
    String dockerfile        = config.dockerfile        ?: 'Dockerfile'
    String prTarget          = config.prTarget          ?: 'pr'
    String runtimeTarget     = config.runtimeTarget     ?: 'runtime'
    // Memes noms de parametres que gaspeziaNodeApi : deux steps de la meme
    // bibliotheque qui nommeraient differemment la meme chose recreeraient, a
    // l'interieur de la bibliotheque, la derive qu'elle est censee supprimer.
    String stacksProdPath    = config.stacksProdKustomization    ?: "k8s/${imageName}/base/kustomization.yaml"
    String stacksStagingPath = config.stacksStagingKustomization ?: "k8s/${imageName}-staging/base/kustomization.yaml"
    String gitCredentialsId  = config.gitCredentialsId  ?: 'github-gaspezia-stacks'
    String discordCredsId    = config.discordCredentialsId ?: 'discord-webhook'
    String sonarBranch       = config.sonarBranch       ?: 'dev'
    Map    extraBuildArgs    = (Map) (config.buildArgs  ?: [:])

    // `configuration` : le front est bati deux fois avec des environnements
    // Angular differents. containsKey et non ?: -- une valeur vide est un choix
    // legitime (front sans configuration), pas une absence de valeur.
    String devConfiguration  = config.containsKey('devConfiguration')  ? config.devConfiguration  : 'staging'
    String prodConfiguration = config.containsKey('prodConfiguration') ? config.prodConfiguration : 'production'

    // Ressources : `requests` = ce qui pese sur l'ordonnancement k8s, `limits` =
    // plafond qui ne reserve rien. D'ou des requests modestes et des limits
    // hautes. Les requests sont figees ici (le right-sizing du 2026-08-05 les a
    // deja harmonisees sur tout le parc) ; seules les limits sont surchargeables,
    // car elles divergent encore d'un depot a l'autre. Les defauts sont ceux de
    // six fronts sur sept ET de gaspeziaNodeApi ; dorangeonTraiteur est le seul
    // a valoir 500m/1/1 et le declare explicitement, ce qui rend l'anomalie
    // visible au lieu de la cacher dans un defaut de bibliotheque.
    // NB: pas d'operateur `<<` ni de `+` sur Map — le bac a sable Groovy de
    // Jenkins les rejette selon la version du plugin script-security. Un `?:`
    // par cle, explicite et sur.
    Map r = (Map) (config.resources ?: [:])
    Map res = [
        jnlpCpuLimit  : r.jnlpCpuLimit   ?: '2',
        kanikoCpuLimit: r.kanikoCpuLimit ?: '2',
        nodeCpuLimit  : r.nodeCpuLimit   ?: '3',
        // 3Gi et non 2Gi : mesure Prometheus sur 7 j, pic reel du conteneur
        // `node` a 2043 Mi pour une limite de 2048 Mi — il touchait le plafond,
        // OOMKill le 2026-08-05.
        nodeMemLimit  : r.nodeMemLimit   ?: '3Gi',
        sonarCpuLimit : r.sonarCpuLimit  ?: '3',
        sonarMemLimit : r.sonarMemLimit  ?: '2Gi',
    ]

    // Equivalent du `options { disableConcurrentBuilds() }` declaratif : deux
    // builds simultanes de la meme branche se marcheraient dessus sur le push
    // gaspezia-stacks.
    properties([disableConcurrentBuilds()])

    // Pas de `defaultContainer` ici : contrairement au `agent { kubernetes {} }`
    // declaratif, le step podTemplate ne connait pas ce parametre et le signale
    // par un WARNING (constate sur DorangeonTraiteur/PR-20 #1). Il est de toute
    // facon inutile : hors d'un bloc `container(...)`, un step s'execute deja
    // dans le conteneur `jnlp`, qui est l'agent lui-meme.
    podTemplate(cloud: 'k8s', yaml: agentPodYaml(partOf, res)) {
        node(POD_LABEL) {
            // Equivalent du bloc `environment {}` : ces variables sont lues par
            // les scripts shell des stages.
            withEnv([
                "DOCKER_REGISTRY_PRIVATE=${registry}",
                "IMAGE_NAME=${imageName}",
                "DOCKERFILE=${dockerfile}"
            ]) {
                try {
                    stage('Checkout') {
                        // Pas de skipDefaultCheckout a declarer : un pipeline
                        // scripte ne fait aucun checkout implicite.
                        checkout scm
                    }

                    stage('Quality (CI)') {
                        // Meme perimetre que le `when` d'origine : PR, dev, main.
                        if (env.CHANGE_ID || env.BRANCH_NAME in ['dev', 'main']) {
                            gaspeziaNodeQuality(sonarBranch: sonarBranch)
                        } else {
                            echo "Quality (CI) saute : ni PR, ni dev, ni main (branche ${env.BRANCH_NAME})."
                        }
                    }

                    stage('CI - PR Compile Check') {
                        if (env.CHANGE_ID) {
                            env.PIPELINE_MODE = 'pr'
                            container('kaniko') {
                                sh """
                                  /kaniko/executor \\
                                    --context dir://\$(pwd) \\
                                    --dockerfile ${dockerfile} \\
                                    --target ${prTarget} \\
                                    --no-push
                                """
                            }
                        } else {
                            echo 'CI - PR Compile Check saute : ce build ne vient pas d\'une PR.'
                        }
                    }

                    stage('Compute Build Context') {
                        if (env.CHANGE_ID) {
                            echo 'Compute Build Context saute : PR (aucune image publiee).'
                        } else {
                            computeBuildContext(registry, imageName, devConfiguration, prodConfiguration)
                        }
                    }

                    stage('Build & Push (Kaniko)') {
                        if (!env.CHANGE_ID && env.PIPELINE_MODE in ['dev', 'main', 'release']) {
                            // Les ARG sont assembles cote Groovy mais injectes en
                            // env : le shell les cite lui-meme, une valeur a espace
                            // ne peut donc pas casser la ligne de commande.
                            String buildArgFlags = kanikoBuildArgs(env.CONFIGURATION, extraBuildArgs)
                            container('kaniko') {
                                withEnv(["KANIKO_BUILD_ARGS=${buildArgFlags}", "KANIKO_TARGET=${runtimeTarget}"]) {
                                    sh '''
                                      set -e
                                      DEST="--destination ${DOCKER_REGISTRY_PRIVATE}/${IMAGE_NAME}:${DOCKER_TAG}"
                                      if [ -n "${DOCKER_TAG_STABLE}" ]; then
                                        DEST="$DEST --destination ${DOCKER_REGISTRY_PRIVATE}/${IMAGE_NAME}:${DOCKER_TAG_STABLE}"
                                      fi
                                      /kaniko/executor \
                                        --context dir://$(pwd) \
                                        --dockerfile ${DOCKERFILE} \
                                        --target ${KANIKO_TARGET} \
                                        ${KANIKO_BUILD_ARGS} \
                                        ${DEST}
                                    '''
                                }
                            }
                        } else {
                            echo "Build & Push saute : PIPELINE_MODE = ${env.PIPELINE_MODE ?: 'n/a'}."
                        }
                    }

                    stage('Update gaspezia-stacks Repo') {
                        if (env.PIPELINE_MODE in ['dev', 'release']) {
                            updateStacks(
                                (env.PIPELINE_MODE == 'release') ? stacksProdPath : stacksStagingPath,
                                // Toujours le tag IMMUABLE (dev-<sha> ou le tag git),
                                // jamais le tag mobile `dev` : ArgoCD doit pointer une
                                // image qui ne bougera plus, sinon un rollback GitOps
                                // ne rejoue pas le meme binaire.
                                (env.PIPELINE_MODE == 'release') ? env.GIT_TAG : env.DOCKER_TAG,
                                imageName,
                                gitCredentialsId
                            )
                        } else {
                            echo "Update gaspezia-stacks saute : PIPELINE_MODE = ${env.PIPELINE_MODE ?: 'n/a'}."
                        }
                    }

                } catch (Exception e) {
                    // Le declaratif marquait le build FAILURE avant d'entrer dans
                    // `post`. En scripte il faut le faire soi-meme, sinon la
                    // notification Discord annonce un SUCCESS sur un build casse.
                    currentBuild.result = 'FAILURE'
                    throw e
                } finally {
                    // Equivalent de `post { always { ... } }`.
                    notifyDiscord(imageName, discordCredsId)
                    cleanWs()
                }
            }
        }
    }
}

// Pod d'agent : un conteneur par outil, qui travaillent EN SEQUENCE (kaniko,
// puis node, puis sonar). Leurs pics ne coincident jamais, mais Kubernetes
// ordonnance sur la SOMME des requests -- d'ou des requests deliberement
// modestes et des limits hautes (right-sizing du 2026-08-05).
String agentPodYaml(String partOf, Map res) {
    return """\
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
        requests: { cpu: "250m", memory: "1536Mi", ephemeral-storage: "4Gi" }
        limits:   { cpu: "${res.kanikoCpuLimit}", memory: "4Gi", ephemeral-storage: "8Gi" }
      volumeMounts:
        - name: nexus-docker-config
          mountPath: /kaniko/.docker
        - name: gaspezia-ca
          mountPath: /kaniko/ssl/certs/additional-ca-cert-bundle.crt
          subPath: ca.crt
        # Jeton de lecture Nexus, pour les depots dont le Dockerfile installe des
        # paquets `@gaspezia/*`. Le chemin est celui que lit leur `RUN`
        # (`mount=/kaniko/nexus-npmrc`) ; les autres depots ne lisent rien ici.
        # Sous `/kaniko/` a dessein : Kaniko n'implemente pas
        # `RUN --mount=type=secret` (il parse la directive et l'ignore), mais il
        # ne capture jamais `/kaniko/` dans ses snapshots — le jeton est donc
        # lisible par le `RUN` sans jamais entrer dans une couche d'image.
        # Montage EN REPERTOIRE, jamais en `subPath` : un `subPath` sur un Secret
        # optionnel absent fait echouer la creation du conteneur et laisserait en
        # ContainerCreating TOUS les pipelines qui partagent ce template.
        - name: nexus-npmrc
          mountPath: /kaniko/nexus-npmrc
    - name: node
      image: node:22-bookworm
      command: ["cat"]
      tty: true
      # Le conteneur `node` execute `pnpm install --frozen-lockfile`
      # (gaspeziaNodeQuality) : memes deux besoins que l'image de build, pour les
      # memes raisons. Les deux variables sont INERTES pour un depot qui ne
      # consomme aucun paquet `@gaspezia/*` (cf. bloc `volumes`).
      env:
        # Node n'utilise PAS le magasin de certificats du systeme : sans cette
        # variable, tout appel a nexus.gaspezia.lan echoue en
        # UNABLE_TO_VERIFY_LEAF_SIGNATURE alors que `curl` passe sans broncher.
        # `update-ca-certificates` ne suffirait pas — il alimente OpenSSL, pas le
        # magasin compile dans Node, qui est celui que pnpm utilise.
        # NODE_EXTRA_CA_CERTS *ajoute* aux racines : une install depuis npmjs est
        # inchangee. Surtout pas `cafile=` dans un .npmrc, qui les *remplace*.
        # Meme chemin que le Jenkinsfile de gaspezia-auth, meme image.
        - name: NODE_EXTRA_CA_CERTS
          value: /etc/ssl/gaspezia/ca.crt
        # Identifiant Nexus. USERCONFIG et non un `.npmrc` ecrit dans le
        # workspace : il remplace le fichier UTILISATEUR (`/root/.npmrc`, qui
        # n'existe pas dans node:22-bookworm), pas le `.npmrc` du depot — lequel
        # porte, chez les consommateurs du SDK, la redirection du scope
        # `@gaspezia` et doit continuer a etre lu. Les deux couches se cumulent.
        # Fichier absent = aucune erreur : pnpm ignore un userconfig introuvable,
        # exactement comme un `~/.npmrc` inexistant.
        - name: NPM_CONFIG_USERCONFIG
          value: /etc/gaspezia/npm/.npmrc
      resources:
        requests: { cpu: "200m", memory: "768Mi" }
        # 3Gi et non 2Gi : mesure Prometheus sur 7 j, pic reel du conteneur `node`
        # a 2043 Mi pour une limite de 2048 Mi — il touchait le plafond, et un
        # OOMKill s'est produit le 2026-08-05. La `request` reste inchangee :
        # elle seule pese sur l'ordonnancement, la limite ne reserve rien.
        limits:   { cpu: "${res.nodeCpuLimit}", memory: "${res.nodeMemLimit}" }
      volumeMounts:
        - name: gaspezia-ca
          mountPath: /etc/ssl/gaspezia
        - name: nexus-npmrc
          mountPath: /etc/gaspezia/npm
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
    # Fragment de `.npmrc` d'authentification au depot npm-private du Nexus
    # (compte de lecture `svc-npm-pull`). Contenu attendu, et RIEN d'autre :
    #   //nexus.gaspezia.lan/repository/npm-private/:_auth=<base64 user:mdp>
    #   //nexus.gaspezia.lan/repository/npm-private/:always-auth=true
    # (`_authToken=` est accepte a la place de `_auth=`.)
    #
    # ATTENTION : AUCUNE ligne `registry=` dans ce Secret. C'est la condition
    # d'INERTIE pour les fronts qui ne consomment aucun paquet `@gaspezia/*`
    # — c'est-a-dire, au 2026-08-07, TOUS ceux qui appellent ce step, puisque
    # bot-twitch-web a encore son propre Jenkinsfile.
    # Monte en config UTILISATEUR de leur conteneur `node`, un
    # fragment qui ne contient que des cles `//host/path/:...` ne s'applique
    # qu'aux requetes vers CE depot : leur registre par defaut reste
    # registry.npmjs.org, leur `.npmrc` de depot n'est pas remplace (couche
    # PROJET, prioritaire), et node:22-bookworm n'a pas de `/root/.npmrc` a
    # ecraser. Cote `kaniko`, leur Dockerfile ne lit jamais
    # /kaniko/nexus-npmrc, et Kaniko ne snapshotte pas /kaniko : rien n'entre
    # dans leurs images. Verifie a la main, fragment monte en userconfig :
    #   pnpm config get registry  ->  https://registry.npmjs.org/  (inchange)
    #
    # Pas de `defaultMode` restrictif : le mode par defaut (0644) laisse le
    # fragment lisible quel que soit l'uid, alors qu'un 0400 le reserverait a
    # root et casserait si le pod venait a tourner en non-root. Meme choix que
    # `nexus-ci`, dont le credential Docker est deja monte ainsi.
    #
    # `optional: true` : le SealedSecret `nexus-npmrc` peut ne pas exister encore
    # dans `jenkins-builds`. Secret absent => repertoire vide, pod sain. C'est ce
    # qui rend ce bloc posable AVANT le Secret, et sans risque pour les autres
    # fronts si le Secret venait a etre retire.
    #
    # `items` fige la cle a `.npmrc` : seul moyen d'avoir un chemin STABLE pour
    # NPM_CONFIG_USERCONFIG cote `node` et pour la recherche du Dockerfile cote
    # `kaniko`. Une cle mal nommee est ignoree sans bloquer le pod (semantique
    # `optional`), et ressort en erreur explicite du garde-fou du Dockerfile
    # plutot qu'en 401 illisible dix minutes plus tard.
    - name: nexus-npmrc
      secret:
        secretName: nexus-npmrc
        optional: true
        items:
          - { key: .npmrc, path: .npmrc }
"""
}

// Traduit la branche courante en mode de pipeline, tag(s) d'image et
// configuration Angular. Seules dev, main et les tags declenchent un CD.
void computeBuildContext(String registry, String imageName, String devConfiguration, String prodConfiguration) {
    if (env.BRANCH_NAME == 'dev') {
        String shortCommit      = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
        env.PIPELINE_MODE       = 'dev'
        env.CONFIGURATION       = devConfiguration
        env.DOCKER_TAG          = "dev-${shortCommit}"
        env.DOCKER_TAG_STABLE   = 'dev'
        env.DOCKER_IMAGE        = "${registry}/${imageName}:${env.DOCKER_TAG}"
        env.DOCKER_IMAGE_STABLE = "${registry}/${imageName}:${env.DOCKER_TAG_STABLE}"

    } else if (env.BRANCH_NAME == 'main') {
        String shortCommit      = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
        env.PIPELINE_MODE       = 'main'
        env.CONFIGURATION       = prodConfiguration
        env.DOCKER_TAG          = "main-${shortCommit}"
        env.DOCKER_TAG_STABLE   = 'main'
        env.DOCKER_IMAGE        = "${registry}/${imageName}:${env.DOCKER_TAG}"
        env.DOCKER_IMAGE_STABLE = "${registry}/${imageName}:${env.DOCKER_TAG_STABLE}"

    } else if (env.TAG_NAME) {
        env.PIPELINE_MODE       = 'release'
        env.CONFIGURATION       = prodConfiguration
        env.GIT_TAG             = env.TAG_NAME
        env.DOCKER_TAG          = env.TAG_NAME
        env.DOCKER_TAG_STABLE   = ''
        env.DOCKER_IMAGE        = "${registry}/${imageName}:${env.DOCKER_TAG}"

    } else {
        env.PIPELINE_MODE       = 'none'
        echo "Branch ${env.BRANCH_NAME} is not handled by CD."
    }

    echo "PIPELINE_MODE = ${env.PIPELINE_MODE}"
    echo "DOCKER_IMAGE  = ${env.DOCKER_IMAGE ?: 'N/A'}"
    echo "CONFIGURATION = ${env.CONFIGURATION ?: 'N/A'}"
}

// `configuration` d'abord (c'est celui que tous les fronts passent), puis les
// ARG supplementaires. Une configuration vide n'emet aucun flag : le Dockerfile
// garde alors son `ARG configuration=production` par defaut.
String kanikoBuildArgs(String configuration, Map extraBuildArgs) {
    List<String> flags = []
    if (configuration) {
        flags << "--build-arg configuration=${configuration}"
    }
    for (String key : extraBuildArgs.keySet()) {
        flags << "--build-arg ${key}=${extraBuildArgs[key]}"
    }
    return flags.join(' ')
}

// Bump du tag d'image dans gaspezia-stacks : c'est CE commit qui declenche le
// deploiement ArgoCD. La mecanique (clone, garde-fou multi-services, reprise sur
// course au push) vit dans `gaspeziaStacksBump` — elle etait dupliquee a trois
// endroits, et le correctif de la course du 2026-08-07 devait s'appliquer aux trois.
void updateStacks(String targetFile, String newTag, String imageName, String gitCredentialsId) {
    gaspeziaStacksBump(
        targetFile: targetFile,
        newTag: newTag,
        imageName: imageName,
        gitCredentialsId: gitCredentialsId
    )
}

// Notification Discord en NATIF (POST webhook via curl) — pas de dependance au
// plugin Jenkins Discord (discordSend). Jamais bloquante : un webhook injoignable
// ne doit pas faire echouer un build par ailleurs vert.
void notifyDiscord(String imageName, String discordCredentialsId) {
    String branchRef = env.TAG_NAME ?: env.BRANCH_NAME
    List<String> lines = ["**Branch:** ${branchRef}"]

    if (env.CHANGE_ID)           { lines << "**PR:** #${env.CHANGE_ID}" }
    if (env.DOCKER_IMAGE)        { lines << "**Image:** ${env.DOCKER_IMAGE}" }
    if (env.DOCKER_IMAGE_STABLE) { lines << "**Stable:** ${env.DOCKER_IMAGE_STABLE}" }
    if (env.PIPELINE_MODE == 'release' && env.GIT_TAG) { lines << "**Tag:** ${env.GIT_TAG}" }

    String result = currentBuild.currentResult
    String icon   = ['SUCCESS': '✅', 'FAILURE': '❌', 'UNSTABLE': '⚠️'].get(result, 'ℹ️')
    def    color  = ['SUCCESS': 3066993, 'FAILURE': 15158332, 'UNSTABLE': 16776960].get(result, 9807270)

    String payload = groovy.json.JsonOutput.toJson([
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

    withCredentials([string(credentialsId: discordCredentialsId, variable: 'DISCORD_WEBHOOK')]) {
        sh '''
          set +x
          curl -sf -H "Content-Type: application/json" \
               -X POST --data @discord-payload.json "$DISCORD_WEBHOOK" \
            || echo "Discord notify failed (non-bloquant)"
        '''
    }
}
