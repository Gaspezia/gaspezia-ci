// =============================================================================
// gaspeziaConformanceAudit — le verrou anti-derive du parc applicatif.
// =============================================================================
// Clone les 13 depots applicatifs (branche par defaut) + cette bibliotheque, les
// confronte au gabarit decrit dans resources/ci/conformance-repos.json, poste un
// rapport unique sur Discord et passe le build en ROUGE des qu'un depot s'ecarte.
//
// POURQUOI UN JOB PERIODIQUE, ET NON UNE ETAPE DANS LA CI DE CHAQUE DEPOT
//   1. Un depot qui derive est, tres exactement, un depot que personne ne
//      touche. Une etape ajoutee au pipeline applicatif ne s'execute QUE si le
//      depot est build : elle ne verrait jamais le depot dormant, qui est le cas
//      le plus frequent de derive.
//   2. La regle « le Jenkinsfile appelle la bibliotheque » ne PEUT PAS etre
//      verifiee par la bibliotheque : un depot qui ne l'appelle plus ne
//      l'execute plus. Un controle interne au pipeline est aveugle a sa propre
//      absence — c'est ce qui rend un audit EXTERNE indispensable.
//   3. Bloquer une PR sur un ecart d'outillage se paie en contournements : le
//      controle finit desactive, donc ne verrouille plus rien. Ici l'echec est
//      porte par un job d'infra que personne n'attend, pas par la PR d'un dev.
//   4. Le patron est deja eprouve dans le parc : build-image-node-gaspezia
//      (gaspezia-build-config) tourne en cron avec la meme notification Discord.
//
// Le complement per-build existe (`node conformance-check.mjs --depot .`, voir
// README) : meme moteur, un seul depot, non bloquant. Il donne un retour le jour
// meme au developpeur ; ce job, lui, est le filet qui ne dort jamais.
//
// Usage (chargement dynamique, aucune config Jenkins globale) :
//   library identifier: 'gaspezia-ci@main', retriever: modernSCM([$class: 'GitSCMSource',
//       remote: 'https://github.com/Gaspezia/gaspezia-ci.git', credentialsId: 'github-gaspezia-stacks'])
//   gaspeziaConformanceAudit()
//
// Credentials Jenkins requis : `github-gaspezia-stacks` (clone des 13 depots),
// `discord-webhook`. Cloud k8s nomme `k8s`, ServiceAccount `jenkins-agent`.
// =============================================================================
def call(Map config = [:]) {

    // `jenkins-gaspezia` (GitHub App, portee organisation) et NON
    // `github-gaspezia-stacks` : ce dernier est scope au seul depot gaspezia-stacks
    // (« Permet de pouvoir modifier le repo gaspezia-stacks »), il renvoie 403 sur
    // les depots applicatifs — constate au premier build de ci-conformance le
    // 2026-08-05, ou les 14 clones ont echoue et l'audit a rapporte 0/14 a tort.
    // Cet audit ne POUSSE rien : il lui faut un acces en LECTURE a tout le parc,
    // ce qui est exactement le role de la GitHub App. C'est aussi le credential
    // qu'utilisent deja les jobs build-image-* pour cloner gaspezia-build-config.
    String gitCredsId     = config.gitCredentialsId     ?: 'jenkins-gaspezia'
    String discordCredsId = config.discordCredentialsId ?: 'discord-webhook'
    String organisation   = config.organisation         ?: 'Gaspezia'
    // Node 24 = la version du gabarit ; l'audit se tient a ce qu'il exige des autres.
    String nodeImage      = config.nodeImage            ?: 'nexus.gaspezia.lan/docker-all/node:24-slim'
    // NB: la periodicite n'est volontairement PAS un parametre. Elle est ecrite
    // en dur dans la directive `triggers` plus bas — c'est une propriete de
    // l'audit, pas de son appelant, et une directive declarative se veut
    // litterale. La regler ici et la voir ecrasee au prochain build serait le
    // pire des deux mondes.

    // Pod minimal : ce job LIT des fichiers texte, il ne compile rien et ne
    // construit aucune image. Pas de kaniko, pas de sonar-scanner. `git` vit
    // dans jnlp (image jenkins-inbound-agent), `node` dans son conteneur.
    String podYaml = """
apiVersion: v1
kind: Pod
metadata:
  labels:
    app.kubernetes.io/component: jenkins-build
    app.kubernetes.io/part-of: gaspezia-ci-conformance
spec:
  serviceAccountName: jenkins-agent
  containers:
    - name: jnlp
      image: nexus.gaspezia.lan/docker-private/jenkins-inbound-agent:gaspezia
      resources:
        requests: { cpu: "100m", memory: "256Mi" }
        limits:   { cpu: "1", memory: "512Mi" }
    - name: node
      image: ${nodeImage}
      command: ["cat"]
      tty: true
      resources:
        requests: { cpu: "100m", memory: "128Mi" }
        limits:   { cpu: "1", memory: "512Mi" }
"""

    pipeline {
        agent {
            kubernetes {
                cloud 'k8s'
                defaultContainer 'jnlp'
                yaml podYaml
            }
        }

        // skipDefaultCheckout : ce job n'a rien a checkout, il clone lui-meme.
        // Le moteur de regles vient de la bibliotheque (libraryResource).
        options {
            disableConcurrentBuilds()
            skipDefaultCheckout(true)
            timestamps()
        }

        // Lundi tot : le rapport arrive avant que la semaine ne commence a
        // deriver. `H` etale la charge sur l'heure (convention Jenkins : ne pas
        // declencher tous les crons a la minute pile).
        // ⚠️ Un trigger declare dans un Jenkinsfile n'est enregistre par Jenkins
        // qu'apres un PREMIER build lance a la main : sans ce build d'amorce,
        // l'audit ne partira jamais tout seul.
        triggers { cron('H 5 * * 1') }

        environment {
            ORGANISATION = "${organisation}"
        }

        stages {

            stage('Deployer le moteur de regles') {
                steps {
                    // `script {}` et non des steps nus : `libraryResource` est ici
                    // en position d'ARGUMENT, ce que le validateur declaratif
                    // n'accepte pas partout. Meme precaution que gaspeziaNodeQuality.
                    script {
                        writeFile file: 'conformance-check.mjs',    text: libraryResource('ci/conformance-check.mjs')
                        writeFile file: 'conformance-selftest.mjs', text: libraryResource('ci/conformance-selftest.mjs')
                        writeFile file: 'conformance-repos.json',   text: libraryResource('ci/conformance-repos.json')
                    }
                }
            }

            // Un controle qui ne detecte plus rien est PIRE que pas de controle :
            // il rassure. On verifie donc d'abord que les sept regles mordent
            // encore, sur des depots factices fabriques pour les violer.
            stage('Auto-test du verrou') {
                steps {
                    container('node') {
                        sh 'node conformance-selftest.mjs'
                    }
                }
            }

            stage('Cloner le parc') {
                steps {
                    container('node') {
                        // La liste des depots est produite par le moteur lui-meme :
                        // une seule source de verite (conformance-repos.json), et
                        // pas de JSON parse cote Groovy (le bac a sable Jenkins
                        // rejette JsonSlurper selon la version du plugin).
                        sh 'node conformance-check.mjs --liste > depots.txt'
                    }
                    withCredentials([usernamePassword(credentialsId: gitCredsId,
                            usernameVariable: 'GIT_USERNAME', passwordVariable: 'GIT_TOKEN')]) {
                        // PAS de `set -e` : un depot inclonable (renomme, archive,
                        // droits du token) doit finir en ECART dans le rapport, pas
                        // faire sauter l'audit des douze autres.
                        sh '''
                          set -u
                          rm -rf parc && mkdir -p parc
                          while read -r depot; do
                            [ -n "$depot" ] || continue
                            if git clone --depth 1 --quiet \
                                 "https://${GIT_USERNAME}:${GIT_TOKEN}@github.com/${ORGANISATION}/${depot}.git" \
                                 "parc/${depot}"; then
                              # Tracer CE QUI a ete audite (branche par defaut + sha
                              # court) : evite les debats sur un rapport d'il y a une
                              # semaine. La branche par defaut est celle que voit un
                              # humain sur GitHub - elle vaut main sur 3 depots et
                              # dev sur les 10 autres, d'ou le clone sans -b.
                              ( cd "parc/${depot}" \
                                && printf '%s@%s' "$(git rev-parse --abbrev-ref HEAD)" "$(git rev-parse --short HEAD)" \
                                   > .audit-ref )
                              echo "clone OK   : ${depot}"
                            else
                              echo "clone ECHEC: ${depot} (comptabilise en ecart)"
                            fi
                          done < depots.txt
                        '''
                    }
                }
            }

            stage('Audit de conformite') {
                steps {
                    container('node') {
                        script {
                            // returnStatus et non un `sh` qui echoue : on veut
                            // TOUJOURS atteindre la notification Discord, qui est
                            // la raison d'etre du job. Le rouge est pose a la main.
                            int rc = sh(script: 'node conformance-check.mjs --racine parc --sortie conformance-report.json',
                                        returnStatus: true)
                            if (rc != 0) {
                                currentBuild.result = 'FAILURE'
                                currentBuild.description = (rc == 1)
                                    ? 'Derive detectee — voir le rapport'
                                    : "Audit en erreur (code ${rc})"
                            } else {
                                currentBuild.description = 'Parc conforme'
                            }
                        }
                    }
                    archiveArtifacts artifacts: 'conformance-report.json', allowEmptyArchive: true
                }
            }
        }

        post {
            always {
                script {
                    // Charge utile produite par le moteur (elle contient le detail
                    // par depot). Si le moteur n'a pas tourne — auto-test rouge,
                    // clone impossible — on fabrique un message minimal : un audit
                    // muet serait indiscernable d'un audit vert.
                    if (!fileExists('discord-payload.json')) {
                        def payload = groovy.json.JsonOutput.toJson([
                            username: 'Conformité CI',
                            embeds: [[
                                title: "❌ Conformité du parc — l'audit n'a pas pu s'exécuter (${currentBuild.currentResult})",
                                description: "Le moteur de règles n'a produit aucun rapport : auto-test en échec, clone impossible ou pod interrompu. **L'absence de rapport n'est pas un parc conforme.**",
                                url: env.BUILD_URL, color: 15158332,
                                footer: [text: 'Audit périodique de conformité CI'],
                            ]],
                        ])
                        writeFile file: 'discord-payload.json', text: payload
                    }
                    catchError(buildResult: 'UNSTABLE', stageResult: 'UNSTABLE') {
                        withCredentials([string(credentialsId: discordCredsId, variable: 'DISCORD_WEBHOOK')]) {
                            sh '''
                              set -eu
                              curl --fail --silent --show-error --max-time 30 \
                                -H "Content-Type: application/json" \
                                --data-binary @discord-payload.json "$DISCORD_WEBHOOK"
                            '''
                        }
                    }
                    cleanWs()
                }
            }
        }
    }
}
