// Bump du tag d'image dans `Gaspezia/gaspezia-stacks` — c'est CE commit qui declenche
// le deploiement ArgoCD.
//
// Pourquoi un `var` a part : les trois pipelines (gaspeziaNodeApi, gaspeziaAngularWeb,
// et le Jenkinsfile autonome de bot-twitch-web) portaient la MEME sequence
// `clone -> sed -> commit -> push origin main`, sans rebase ni reprise. Deux releases
// lancees a quelques secondes d'intervalle clonent donc le meme `main`, commitent chacune
// par-dessus, et la seconde a pousser est rejetee en `non-fast-forward`. L'image est bien
// dans le Nexus, mais le deploiement n'a pas lieu et le build passe au rouge pour une
// raison qui n'a rien a voir avec le code. Incident observe le 2026-08-07 sur
// bot-twitch-web v1.21.0.
//
// Le remede tient en une boucle : quand le push est rejete, on repart de l'etat distant
// et on REJOUE le `sed` au lieu de rebaser le commit. Poser un tag est une operation
// idempotente et absolue (« newTag vaut X »), pas un delta — la rejouer sur une base plus
// recente donne toujours le bon resultat, la ou un rebase peut conflit sur la meme ligne.
//
// Ce que ce correctif ne fait PAS : serialiser les builds. Aucun `lock()`, aucune file
// d'attente — deux pipelines continuent de tourner de bout en bout en parallele et ne se
// croisent que sur la fraction de seconde du push. C'etait la contrainte : regler la
// course sans rien ralentir.
//
// Parametres :
//   targetFile        (obligatoire) chemin du kustomization dans gaspezia-stacks
//   newTag            (obligatoire) tag a poser
//   imageName         (obligatoire) sert au message de commit
//   gitCredentialsId  (def. 'github-gaspezia-stacks')
//   maxAttempts       (def. 5) tentatives de push avant echec du build
void call(Map config = [:]) {
    String targetFile = config.targetFile
    String newTag     = config.newTag
    String imageName  = config.imageName
    if (!targetFile || !newTag || !imageName) {
        error 'gaspeziaStacksBump: `targetFile`, `newTag` et `imageName` sont obligatoires.'
    }
    String gitCredentialsId = config.gitCredentialsId ?: 'github-gaspezia-stacks'
    int maxAttempts         = (config.maxAttempts ?: 5) as int
    String pipelineMode     = env.PIPELINE_MODE ?: 'manual'

    withCredentials([usernamePassword(
        credentialsId: gitCredentialsId,
        usernameVariable: 'GIT_USERNAME',
        passwordVariable: 'GIT_TOKEN'
    )]) {
        sh """
          set -e
          rm -rf gaspezia-stacks
          # --depth 1 : on ne lit jamais l'historique, seulement la pointe de main.
          git clone --depth 1 https://\${GIT_USERNAME}:\${GIT_TOKEN}@github.com/Gaspezia/gaspezia-stacks.git
          cd gaspezia-stacks

          if [ ! -f "${targetFile}" ]; then
            echo "Skipping: ${targetFile} does not exist"
            exit 0
          fi

          git config user.name "Jenkins"
          git config user.email "jenkins@gaspezia.lan"

          # ---- Garde-fou : le `sed` ci-dessous reecrit TOUTES les lignes newTag du
          # fichier. C'est voulu — une API Node y declare son image et son image
          # `-migrate`, qui partagent forcement le tag de release. Ca cesse de l'etre si
          # le fichier decrit plusieurs services distincts (k8s/gaspezia-mc en aligne
          # sept, chacun avec son propre tag) : un bump y ecraserait les six autres.
          # On compte donc les familles d'images reellement presentes, en appariant
          # chaque `newTag:` au `- name:` qui le precede immediatement — ce qui evite de
          # confondre une image avec les `- name:` d'un configMapGenerator.
          families=\$(awk '
            /^[[:space:]]*-[[:space:]]*name:[[:space:]]*/ {
              line = \$0
              sub(/^[[:space:]]*-[[:space:]]*name:[[:space:]]*/, "", line)
              sub(/[[:space:]]*(#.*)?\$/, "", line)
              last = line
              next
            }
            /^[[:space:]]*newTag:/ { if (last != "") print last }
          ' "${targetFile}" | sed -e 's|.*/||' -e 's|-migrate\$||' | sort -u)
          family_count=\$(printf '%s\\n' "\$families" | grep -c . || true)

          if [ "\$family_count" -gt 1 ]; then
            echo "ERREUR: ${targetFile} declare plusieurs images distinctes :"
            printf '  - %s\\n' \$families
            echo "Un bump global y ecraserait les tags des autres services. Abandon."
            exit 1
          fi

          attempt=1
          while : ; do
            # Le sed est rejoue a chaque tentative, sur l'etat courant d'origin/main.
            sed -i "s|newTag: .*|newTag: ${newTag}|" "${targetFile}"
            git add "${targetFile}"

            if git diff --cached --quiet; then
              echo "No change detected in ${targetFile} (deja a ${newTag})"
              exit 0
            fi

            git commit -q -m "chore(${imageName}): bump image to ${newTag} (${pipelineMode})"

            if git push origin HEAD:main; then
              echo "gaspezia-stacks: ${targetFile} -> ${newTag} (tentative \$attempt)"
              exit 0
            fi

            if [ "\$attempt" -ge "${maxAttempts}" ]; then
              echo "ERREUR: push refuse apres ${maxAttempts} tentatives."
              exit 1
            fi

            # Quelqu'un a pousse entre-temps. Attente decorrelee : \$\$ est le PID du shell,
            # donc different d'un pod d'agent a l'autre — deux builds qui perdent la course
            # au meme instant ne repartent pas ensemble.
            delay=\$(( attempt * 2 + \$\$ % 5 ))
            echo "Push refuse (course sur main), nouvelle tentative dans \${delay}s..."
            sleep "\$delay"

            git fetch --depth 1 origin main
            git reset --hard FETCH_HEAD
            attempt=\$(( attempt + 1 ))
          done
        """
    }
}
