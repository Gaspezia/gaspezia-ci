// CI qualité Gaspezia (Java/Maven — plugins Minecraft) : tests + coverage JaCoCo + SonarQube INFO -> Discord.
// DEV-ONLY (SonarQube CE mono-branche ; les PR sont déjà couvertes par la stage PR Verify du plugin).
// UN seul mvn (test + jacoco + sonar) pour éviter tout double-build.
//   stage('Quality (CI)') {
//     when { branch 'dev' }
//     steps { script {
//       library identifier: 'gaspezia-ci@main', retriever: modernSCM([$class: 'GitSCMSource',
//         remote: 'https://github.com/Gaspezia/gaspezia-ci.git', credentialsId: 'github-gaspezia-stacks'])
//       gaspeziaJavaQuality(projectKey: 'GAPI')
//     } }
//   }
// Pre-requis repo : pod avec conteneurs 'maven' (déjà là) + 'node' (à ajouter, pour le reporter).
// Credentials Jenkins : sonarqube-token, discord-webhook. Non bloquant. JaCoCo/sonar via goals CLI (rien dans le pom).
def call(Map config = [:]) {
    String sonarHost   = config.sonarHostUrl  ?: 'https://sonarqube.gaspezia.fr'
    String sonarBranch = config.sonarBranch   ?: 'dev'
    String jacocoVer   = config.jacocoVersion ?: '0.8.15'
    String projectKey  = config.projectKey
    if (!projectKey) { error('gaspeziaJavaQuality: projectKey requis') }
    if (env.BRANCH_NAME != sonarBranch) {
        echo "gaspeziaJavaQuality: analyse sur '${sonarBranch}' uniquement — '${env.BRANCH_NAME}' ignoree."
        return
    }

    stage('Quality — tests + coverage + Sonar (info)') {
        catchError(buildResult: 'SUCCESS', stageResult: 'UNSTABLE') {
            withCredentials([string(credentialsId: 'sonarqube-token', variable: 'SONAR_TOKEN')]) {
                container('maven') {
                    withEnv(["JV=${jacocoVer}", "SONAR_HOST=${sonarHost}", "PKEY=${projectKey}"]) {
                        sh 'mvn -B -ntp org.jacoco:jacoco-maven-plugin:"$JV":prepare-agent test org.jacoco:jacoco-maven-plugin:"$JV":report sonar:sonar -Dsonar.host.url="$SONAR_HOST" -Dsonar.token="$SONAR_TOKEN" -Dsonar.projectKey="$PKEY" -Dsonar.projectName="$PKEY"'
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
