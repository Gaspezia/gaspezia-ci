// CI qualité Gaspezia (Java/Maven — plugins Minecraft) : build+tests+JaCoCo (SOFT)
// + SonarQube INFO -> Discord (branche dev). Chargement dynamique (aucune config Jenkins globale).
//   stage('Quality (CI)') {
//     when { anyOf { changeRequest(); branch 'dev'; branch 'main' } }
//     steps { script {
//       library identifier: 'gaspezia-ci@main', retriever: modernSCM([$class: 'GitSCMSource',
//         remote: 'https://github.com/Gaspezia/gaspezia-ci.git', credentialsId: 'github-gaspezia-stacks'])
//       gaspeziaJavaQuality(projectKey: 'GAPI')
//     } }
//   }
// Pre-requis repo : pod avec conteneurs 'maven' (déjà là) + 'node' (à ajouter, pour le reporter).
// Credentials Jenkins : sonarqube-token, discord-webhook. JaCoCo/sonar NON requis dans le pom (goals CLI).
def call(Map config = [:]) {
    String sonarHost   = config.sonarHostUrl ?: 'https://sonarqube.gaspezia.fr'
    String sonarBranch = config.sonarBranch  ?: 'dev'
    String jacocoVer   = config.jacocoVersion ?: '0.8.15'
    String projectKey  = config.projectKey
    if (!projectKey) { error('gaspeziaJavaQuality: projectKey requis') }

    stage('Build & Test') {
        catchError(buildResult: 'SUCCESS', stageResult: 'UNSTABLE') {
            container('maven') {
                withEnv(["JV=${jacocoVer}"]) {
                    sh 'mvn -B -ntp org.jacoco:jacoco-maven-plugin:"$JV":prepare-agent test org.jacoco:jacoco-maven-plugin:"$JV":report'
                }
            }
        }
    }

    if (env.BRANCH_NAME == sonarBranch) {
        stage('SonarQube (info)') {
            catchError(buildResult: 'SUCCESS', stageResult: 'UNSTABLE') {
                withCredentials([string(credentialsId: 'sonarqube-token', variable: 'SONAR_TOKEN')]) {
                    container('maven') {
                        withEnv(["SONAR_HOST=${sonarHost}", "PKEY=${projectKey}"]) {
                            sh 'mvn -B -ntp sonar:sonar -Dsonar.host.url="$SONAR_HOST" -Dsonar.token="$SONAR_TOKEN" -Dsonar.projectKey="$PKEY" -Dsonar.projectName="$PKEY"'
                        }
                    }
                    container('node') {
                        writeFile file: '.sonar-report-to-discord.mjs', text: libraryResource('ci/sonar-report-to-discord.mjs')
                        withEnv(["REPORT_TASK=target/sonar/report-task.txt"]) {
                            withCredentials([string(credentialsId: 'discord-webhook', variable: 'DISCORD_WEBHOOK')]) {
                                sh 'node .sonar-report-to-discord.mjs'
                            }
                        }
                    }
                }
            }
        }
    }
}
