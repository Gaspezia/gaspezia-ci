// CI qualité Gaspezia (Node/TS) : lint + tests+coverage (SOFT) + SonarQube INFO -> Discord (branche dev).
// Chargement dynamique (aucune config Jenkins globale requise) :
//   stage('Quality (CI)') {
//     when { anyOf { changeRequest(); branch 'dev'; branch 'main' } }
//     steps { script {
//       library identifier: 'gaspezia-ci@main', retriever: modernSCM([$class: 'GitSCMSource',
//         remote: 'https://github.com/Gaspezia/gaspezia-ci.git', credentialsId: 'github-gaspezia-stacks'])
//       gaspeziaNodeQuality()
//     } }
//   }
// Pre-requis repo : pod avec conteneurs 'node' + 'sonar-scanner' ; fichier sonar-project.properties.
// Credentials Jenkins : sonarqube-token, discord-webhook. Sonar INFO ne bloque JAMAIS.
def call(Map config = [:]) {
    String sonarHost   = config.sonarHostUrl ?: 'https://sonarqube.gaspezia.fr'
    String pnpmVersion = config.pnpmVersion  ?: '10.23.0'
    String sonarBranch = config.sonarBranch  ?: 'dev'

    stage('Lint & Test') {
        catchError(buildResult: 'SUCCESS', stageResult: 'UNSTABLE') {
            container('node') {
                withEnv(["PNPM_VERSION=${pnpmVersion}"]) {
                    sh '''
                      set +e
                      export DATABASE_URL="${DATABASE_URL:-postgresql://ci:ci@localhost:5432/ci?schema=public}"
                      corepack enable
                      corepack prepare pnpm@"$PNPM_VERSION" --activate
                      pnpm install --frozen-lockfile || exit 1
                      has(){ SCRIPT_NAME="$1" node -e 'process.exit(require("./package.json").scripts?.[process.env.SCRIPT_NAME]?0:1)' 2>/dev/null; }
                      TESTSCRIPT=$(node -e 'console.log((require("./package.json").scripts||{}).test||"")' 2>/dev/null)
                      if has prisma:generate; then pnpm prisma:generate; fi
                      rc=0
                      if has lint; then pnpm lint || { echo ">> lint: problemes (non bloquant)"; rc=1; }; else echo ">> pas de script lint"; fi
                      if has test:cov; then pnpm test:cov || { echo ">> tests: echec (non bloquant)"; rc=1; };
                      elif echo "$TESTSCRIPT" | grep -q "ng test"; then echo ">> Angular (ng test): coverage necessite Chrome headless -> saute (Sonar statique seul)";
                      elif has test; then CI=true pnpm test || { echo ">> tests: echec/absents (non bloquant)"; rc=1; };
                      else echo ">> pas de tests"; fi
                      exit $rc
                    '''
                }
            }
        }
    }

    if (env.BRANCH_NAME == sonarBranch) {
        stage('SonarQube (info)') {
            catchError(buildResult: 'SUCCESS', stageResult: 'UNSTABLE') {
                withCredentials([string(credentialsId: 'sonarqube-token', variable: 'SONAR_TOKEN')]) {
                    container('sonar-scanner') {
                        withEnv(["SONAR_HOST=${sonarHost}"]) {
                            sh 'sonar-scanner -Dsonar.host.url="$SONAR_HOST" -Dsonar.token="$SONAR_TOKEN"'
                        }
                    }
                    container('node') {
                        writeFile file: '.sonar-report-to-discord.mjs', text: libraryResource('ci/sonar-report-to-discord.mjs')
                        withCredentials([string(credentialsId: 'discord-webhook', variable: 'DISCORD_WEBHOOK')]) {
                            sh 'node .sonar-report-to-discord.mjs'
                        }
                    }
                }
            }
        }
    }
}
