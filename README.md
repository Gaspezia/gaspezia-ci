# gaspezia-ci — Jenkins Shared Library (CI qualité Gaspezia)

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
