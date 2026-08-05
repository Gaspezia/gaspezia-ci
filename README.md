# gaspezia-ci — Jenkins Shared Library (CI Gaspezia)

| Step | Rôle |
|---|---|
| `gaspeziaAngularWeb()` | **pipeline complet** d'un front Angular (pod d'agent, quality, PR compile check, build+push Kaniko, bump gaspezia-stacks, Discord) |
| `gaspeziaNodeQuality()` | lint + tests+coverage (SOFT, jamais bloquant) + **SonarQube INFO** posté sur **Discord** |
| `gaspeziaJavaQuality()` | équivalent Maven/JaCoCo pour les plugins Minecraft |

---

## `gaspeziaAngularWeb` — front Angular complet

Les 7 fronts Angular portaient le **même** `Jenkinsfile` de ~270 lignes, recopié à la
main. Chaque correctif devait donc être réappliqué 7 fois, et il ne l'était jamais
entièrement : au 2026-08-05 la limite CPU de `jnlp` valait `500m` sur
dorangeonTraiteur et `2` ailleurs, et `bot-discord-web` poussait dans gaspezia-stacks
le tag **mobile** (`dev`) là où les autres poussent le tag **immuable** (`dev-<sha>`) —
ce qui casse silencieusement la traçabilité GitOps.

### Usage (chargement dynamique, aucune config Jenkins globale)
```groovy
library identifier: 'gaspezia-ci@main', retriever: modernSCM([$class: 'GitSCMSource',
    remote: 'https://github.com/Gaspezia/gaspezia-ci.git', credentialsId: 'github-gaspezia-stacks'])

gaspeziaAngularWeb(imageName: 'dorangeontraiteur-web', devConfiguration: 'staging')
```

### Ce que le step fait
| Étape | Quand |
|---|---|
| pod d'agent k8s (`jnlp` + `kaniko` + `node` + `sonar-scanner`) | toujours |
| `Checkout` | toujours |
| `Quality (CI)` → `gaspeziaNodeQuality()` | PR, `dev`, `main` |
| `CI - PR Compile Check` (kaniko `--target pr --no-push`) | PR |
| `Compute Build Context` (mode, tags, `configuration`) | hors PR |
| `Build & Push (Kaniko)` (`--target runtime`, tag immuable **+** tag mobile) | `dev`, `main`, tag git |
| `Update gaspezia-stacks Repo` (bump `newTag`, **toujours le tag immuable**) | `dev`, tag git |
| notification Discord (curl natif) + `cleanWs()` | toujours, même en échec |

Mapping branche → build :

| Branche | mode | tag immuable | tag mobile | `--build-arg configuration=` | kustomization bumpé |
|---|---|---|---|---|---|
| `dev` | `dev` | `dev-<sha>` | `dev` | `devConfiguration` | `…-staging` |
| `main` | `main` | `main-<sha>` | `main` | `prodConfiguration` | — |
| tag git | `release` | `<tag>` | — | `prodConfiguration` | prod |
| autre | `none` | — | — | — | — |

### Paramètres

| Paramètre | Défaut | Note |
|---|---|---|
| `imageName` | **requis** | nom de l'image ET racine des chemins gaspezia-stacks |
| `partOf` | `imageName` | label `app.kubernetes.io/part-of` du pod |
| `registry` | `nexus.gaspezia.lan/docker-private` | |
| `dockerfile` | `Dockerfile` | |
| `prTarget` / `runtimeTarget` | `pr` / `runtime` | étages du Dockerfile |
| `devConfiguration` | `staging` | `''` = aucun `--build-arg configuration` |
| `prodConfiguration` | `production` | idem |
| `buildArgs` | `[:]` | `--build-arg` supplémentaires |
| `stacksProdPath` | `k8s/<imageName>/base/kustomization.yaml` | |
| `stacksStagingPath` | `k8s/<imageName>-staging/base/kustomization.yaml` | |
| `jnlpCpuLimit` / `nodeCpuLimit` / `sonarCpuLimit` | `500m` / `1` / `1` | voir ci-dessous |
| `gitCredentialsId` / `discordCredentialsId` / `sonarBranch` | `github-gaspezia-stacks` / `discord-webhook` / `dev` | |

Exemples réels du parc :

```groovy
// front bâti en `production` même sur dev (pas d'environnement staging Angular)
gaspeziaAngularWeb(imageName: 'gaspezia-asso', devConfiguration: 'production',
                   jnlpCpuLimit: '2', nodeCpuLimit: '3', sonarCpuLimit: '3')
```

**Les `limits` CPU sont paramétrables à dessein.** Elles divergent d'un dépôt à
l'autre depuis longtemps ; les harmoniser est une décision à prendre à part, pas un
effet de bord d'un refactoring de CI. Les **`requests`**, elles, sont figées dans la
bibliothèque : c'est sur elles que Kubernetes ordonnance, et le right-sizing du
2026-08-05 les a déjà harmonisées (les conteneurs travaillent en séquence, leurs pics
ne coïncident jamais, mais k8s additionne leurs requests).

### Pré-requis dans le dépôt consommateur
- `Dockerfile` à 3 étages : `build` → `pr` → `runtime`.
- `sonar-project.properties`.
- Credentials Jenkins : `github-gaspezia-stacks`, `discord-webhook`, `sonarqube-token`.
- Cloud Kubernetes Jenkins nommé `k8s`, ServiceAccount `jenkins-agent`, secret
  `nexus-ci` et ConfigMap `gaspezia-ca` dans le namespace des agents.

### Pourquoi un pipeline *scripté* et non déclaratif
Le `agent { kubernetes { yaml … } }` d'un pipeline déclaratif est évalué **avant**
tout `script {}` : il ne peut donc pas venir d'une bibliothèque. Le `podTemplate`
scripté, lui, s'instancie depuis le code de la bibliothèque — c'est la seule façon de
sortir *aussi* le pod du `Jenkinsfile`. Deux conséquences visibles dans les logs, sans
effet fonctionnel :
- la bibliothèque est chargée une fois au tout début (et non dans `Quality (CI)`) ;
- un stage non applicable affiche un message de saut au lieu du `Stage … skipped due
  to when conditional` du déclaratif.

---

## `gaspeziaNodeQuality` — lint + tests + Sonar (info)

```groovy
stage('Quality (CI)') {
  when { anyOf { changeRequest(); branch 'dev'; branch 'main' } }
  steps { script {
    library identifier: 'gaspezia-ci@main', retriever: modernSCM([$class: 'GitSCMSource',
      remote: 'https://github.com/Gaspezia/gaspezia-ci.git', credentialsId: 'github-gaspezia-stacks'])
    gaspeziaNodeQuality()
  } }
}
```
Appelé automatiquement par `gaspeziaAngularWeb` — cet usage direct ne reste utile
qu'aux dépôts **non** Angular (API NestJS).

### Pré-requis
- Pod agent avec les conteneurs **`node`** et **`sonar-scanner`**.
- Un fichier **`sonar-project.properties`** (projectKey, sources,
  `sonar.javascript.lcov.reportPaths=coverage/lcov.info`).
- Credentials : **`sonarqube-token`**, **`discord-webhook`**.

Le step auto-détecte les scripts npm (`prisma:generate`, `lint`, `test:cov`/`test`) →
marche pour NestJS et Angular. La version pnpm vient du champ `packageManager` du dépôt
(corepack). Sonar = **information**, ne bloque jamais. SonarQube CE étant mono-branche,
l'analyse ne tourne que sur `dev`.
