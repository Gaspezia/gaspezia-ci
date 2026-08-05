# gaspezia-ci — Jenkins Shared Library (CI Gaspezia)

| Step | Rôle |
| --- | --- |
| `gaspeziaNodeApi()` | **Pipeline CD complet** des API Node/NestJS (agent k8s, Kaniko, bump `gaspezia-stacks`, Discord) |
| `gaspeziaNodeQuality()` | Étape qualité seule (lint + tests + SonarQube INFO) |
| `gaspeziaJavaQuality()` | Étape qualité seule pour les plugins Java/Maven |

---

## `gaspeziaNodeApi()` — pipeline CD complet

Remplace les ~295 lignes de Jenkinsfile dupliquées à l'identique dans `bot-twitch-api`,
`mon-labo-gourmand-api`, `gaspezia-members-api-v2`, `email-sender-api`… Le Jenkinsfile du
repo consommateur se réduit à :

```groovy
library identifier: 'gaspezia-ci@main', retriever: modernSCM([$class: 'GitSCMSource',
    remote: 'https://github.com/Gaspezia/gaspezia-ci.git', credentialsId: 'github-gaspezia-stacks'])
gaspeziaNodeApi(imageName: 'bot-twitch-api')
```

### Contrat de comportement (ce que la prod consomme — à ne pas casser)

| Déclencheur | Images poussées | `gaspezia-stacks` |
| --- | --- | --- |
| Pull request | *aucune* (`--target pr --no-push`) | *aucun bump* |
| Branche `dev` | `<img>:dev-<sha>` + `<img>:dev` (idem `-migrate`) | bump **staging** |
| Branche `main` | `<img>:main-<sha>` + `<img>:main` (idem `-migrate`) | *aucun bump* (main n'est pas déployé) |
| Tag `vX.Y.Z` | `<img>:vX.Y.Z` **sans tag mobile** | bump **prod** |

Dans tous les cas : notification Discord native (curl) puis `cleanWs()`.

### Paramètres

| Clé | Défaut | Remarque |
| --- | --- | --- |
| `imageName` | *(obligatoire)* | pilote le nom d'image, les labels et les chemins kustomization |
| `registry` | `nexus.gaspezia.lan/docker-private` | |
| `stacksProdKustomization` | `k8s/<imageName>/base/kustomization.yaml` | à surcharger si le nom diffère (cas `email-sender-api`) |
| `stacksStagingKustomization` | `k8s/<imageName>-staging/base/kustomization.yaml` | idem |
| `buildMigrateImage` | `true` | `false` si le repo n'a pas de gate pré-migration (pas de Prisma) |
| `partOf` | `imageName` | label `app.kubernetes.io/part-of` |
| `nodeImage` | `node:22-bookworm` | conteneur CI, **pas** le runtime applicatif |
| `resources` | cf. source | plafonds par conteneur. Clés : `jnlpCpuLimit`, `kanikoCpuLimit`, `kanikoMigrateCpuLimit`, `kanikoMigrateMemLimit`, `nodeCpuLimit`, `nodeMemLimit`, `sonarCpuLimit`, `sonarMemLimit`. `nodeMemLimit` à `3Gi` (OOMKill du 2026-08-05) ; `kanikoMigrateCpuLimit` retombe sur `kanikoCpuLimit` |
| `gitCredentialsId` / `discordCredentialsId` | `github-gaspezia-stacks` / `discord-webhook` | |

### Pré-requis dans le repo consommateur
- Un `Dockerfile` multi-stage exposant les targets **`pr`**, **`runtime`** et (si `buildMigrateImage`) **`migrate`**.
- Un `sonar-project.properties` (l'étape qualité est déléguée à `gaspeziaNodeQuality`).
- Credentials Jenkins : `nexus-ci`, `github-gaspezia-stacks`, `discord-webhook`, `sonarqube-token`.

---

## `gaspeziaNodeQuality()`

`gaspeziaNodeQuality()` : lint + tests+coverage (SOFT, jamais bloquant) + analyse **SonarQube INFO** postée sur **Discord** (branche `dev` uniquement — SonarQube CE = mono-branche).

## Usage (chargement dynamique, aucune config Jenkins globale)
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

## Pré-requis dans le repo consommateur
- Pod agent avec les conteneurs **`node`** (node:22-bookworm) et **`sonar-scanner`** (sonarsource/sonar-scanner-cli).
- Un fichier **`sonar-project.properties`** (projectKey, sources, `sonar.javascript.lcov.reportPaths=coverage/lcov.info`).
- Credentials Jenkins : **`sonarqube-token`** (Secret text), **`discord-webhook`** (Secret text).

Le step auto-détecte les scripts npm (`prisma:generate`, `lint`, `test:cov`/`test`) → marche pour NestJS et Angular. Sonar = **information**, ne bloque jamais.
