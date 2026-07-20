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
// La version pnpm vient du champ packageManager du repo (corepack). ng test (Angular) = sauté (Chrome requis).
def call(Map config = [:]) {
    String sonarHost   = config.sonarHostUrl ?: 'https://sonarqube.gaspezia.fr'
    String sonarBranch = config.sonarBranch  ?: 'dev'

    stage('Lint & Test') {
        catchError(buildResult: 'SUCCESS', stageResult: 'UNSTABLE') {
            container('node') {
                sh '''
                  set +e
                  export DATABASE_URL="${DATABASE_URL:-postgresql://ci:ci@localhost:5432/ci?schema=public}"
                  export COREPACK_ENABLE_DOWNLOAD_PROMPT=0
                  corepack enable
                  pnpm install --frozen-lockfile || exit 1
                  has(){ SCRIPT_NAME="$1" node -e 'process.exit(require("./package.json").scripts?.[process.env.SCRIPT_NAME]?0:1)' 2>/dev/null; }
                  TESTSCRIPT=$(node -e 'console.log((require("./package.json").scripts||{}).test||"")' 2>/dev/null)
                  if has prisma:generate; then pnpm prisma:generate; fi
                  rc=0
                  if has lint; then pnpm lint || { echo ">> lint: problemes (non bloquant)"; rc=1; }; else echo ">> pas de script lint"; fi
                  # Runner Angular : Vitest tourne en jsdom (headless), Karma exige Chrome.
                  # On ne saute donc QUE Karma.
                  ng_runner(){ node -e "
                    const fs=require('fs');
                    if(!fs.existsSync('angular.json')) process.exit(2);
                    const a=JSON.parse(fs.readFileSync('angular.json','utf8'));
                    for(const p of Object.values(a.projects||{})){
                      const t=(p.architect||p.targets||{}).test; if(!t) continue;
                      const b=String(t.builder||'');
                      if(b.includes('@angular/build:unit-test')){console.log('vitest');process.exit(0);}
                      if(b.includes('karma')){console.log('karma');process.exit(0);}
                    }
                    process.exit(2);" 2>/dev/null; }
                  if has test:cov; then pnpm test:cov || { echo ">> tests: echec (non bloquant)"; rc=1; };
                  elif echo "$TESTSCRIPT" | grep -q "ng test"; then
                    RUNNER=$(ng_runner) || RUNNER=inconnu
                    if [ "$RUNNER" = "karma" ]; then
                      echo ">> Angular/Karma: exige Chrome headless -> saute (Sonar statique seul)";
                    else
                      echo ">> Angular/$RUNNER: tests executes. Ajoute un script test:cov pour remonter la couverture a Sonar.";
                      CI=true pnpm test || { echo ">> tests: echec (non bloquant)"; rc=1; };
                    fi;
                  elif has test; then CI=true pnpm test || { echo ">> tests: echec/absents (non bloquant)"; rc=1; };
                  else echo ">> pas de tests"; fi
                  exit $rc
                '''
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
