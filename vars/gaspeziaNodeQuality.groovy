// CI qualite Gaspezia (Node/TS) : lint + tests+coverage + SonarQube INFO -> Discord (branche dev).
//
// ⚠️ LE BLOCAGE EST OPT-IN, PAR DEPOT : `gaspeziaNodeQuality(blocking: true)`.
//    Par defaut rien ne change — un lint ou des tests rouges laissent le build
//    VERT (stage UNSTABLE). C'est l'historique de cette bibliotheque, et la
//    basculer d'un coup rendrait rouge, le meme matin, tout depot dont la suite
//    est cassee, sans que personne ne l'ait decide ni ne sache lequel.
//
//    Ce defaut n'est PAS une position sur ce qui est souhaitable : une suite
//    rouge qui part en production est un vrai defaut de chaine — vecu le
//    2026-08-12 sur bot-twitch, ou « CI verte » a ete lu six fois comme une
//    garantie de tests alors qu'elle ne garantissait que la compilation. C'est
//    un chemin de migration : chaque depot leve le drapeau quand sa suite est
//    verte, et l'objectif est que `blocking: true` devienne la norme, puis le
//    defaut.
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
    // Opt-in : cf. l'avertissement en tete de fichier.
    boolean blocking   = config.blocking ?: false

    stage('Lint & Test') {
        // `catchError` est ce qui, et ce qui SEUL, avale le code de retour et
        // laisse le build vert. En mode bloquant on ne l'enveloppe donc pas :
        // l'echec du `sh` fait tomber le stage, donc le build, donc la PR.
        if (blocking) {
            lintEtTests()
        } else {
            catchError(buildResult: 'SUCCESS', stageResult: 'UNSTABLE') { lintEtTests() }
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

/**
 * Le corps reel : installation, lint, tests, couverture.
 *
 * Extrait de `call` en METHODE, et non en fermeture affectee a une variable :
 * c'est le patron que Jenkins transforme correctement en CPS. Une fermeture
 * fonctionne en apparence, puis casse a la reprise apres redemarrage du
 * controleur.
 *
 * ⚠️ Le script se termine deja par `exit \$rc` : il REND un code non nul quand
 * le lint ou les tests echouent. C'est `catchError`, cote appelant, qui le
 * neutralisait — jamais ce script. Et ne pas « corriger » le `set +e` : sans
 * lui, le premier echec sortirait avant d'avoir lance les suivants, et on ne
 * saurait jamais si les tests passent quand le lint est rouge.
 */
void lintEtTests() {
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
                  if has lint; then pnpm lint || { echo ">> lint: problemes"; rc=1; }; else echo ">> pas de script lint"; fi
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
                  if has test:cov; then pnpm test:cov || { echo ">> tests: echec"; rc=1; };
                  elif echo "$TESTSCRIPT" | grep -q "ng test"; then
                    RUNNER=$(ng_runner) || RUNNER=inconnu
                    if [ "$RUNNER" = "karma" ]; then
                      echo ">> Angular/Karma: exige Chrome headless -> saute (Sonar statique seul)";
                    else
                      echo ">> Angular/$RUNNER: tests executes. Ajoute un script test:cov pour remonter la couverture a Sonar.";
                      CI=true pnpm test || { echo ">> tests: echec"; rc=1; };
                    fi;
                  elif has test; then CI=true pnpm test || { echo ">> tests: echec/absents"; rc=1; };
                  else echo ">> pas de tests"; fi

                  # Controles maison du depot (`check:*`). On n'execute QUE ceux
                  # qui sont autonomes : `check:layout*` exige Chrome, et
                  # `check:contract` exige le depot voisin — il sort en erreur
                  # plutot que de se sauter en silence, ce qui est voulu mais
                  # incompatible avec une CI qui ne checkout qu'un depot.
                  for CTRL in check:i18n check:premium; do
                    if has "$CTRL"; then
                      pnpm "$CTRL" || { echo ">> $CTRL: echec"; rc=1; }
                    fi
                  done

                  exit $rc
                '''
            }
}
