// =============================================================================
// Job Jenkins : ci-conformance  (audit periodique du parc applicatif)
// =============================================================================
// Point d'entree « Pipeline script from SCM » du verrou anti-derive. Tout le
// pipeline vit dans la bibliotheque (vars/gaspeziaConformanceAudit.groovy) : ce
// fichier ne fait que la charger, exactement comme un Jenkinsfile applicatif.
//
// POURQUOI CE FICHIER EST ICI ET NON DANS gaspezia-build-config
// gaspezia-build-config heberge les pipelines qui CONSTRUISENT des images
// (build-image-*), et c'est bien son cron + sa notification Discord qui servent
// de modele ici. Mais cet audit ne construit rien : il verifie le gabarit porte
// par gaspezia-ci. Ses regles, son moteur et ses steps doivent bouger d'un seul
// tenant avec la bibliotheque — changer le defaut memoire du pod dans
// gaspeziaNodeApi et la regle qui le controle sont le meme commit. Les separer,
// c'est reintroduire exactement la derive que ce job existe pour empecher.
//
// CREATION DU JOB (a faire une fois, cote Jenkins UI)
//   Type            : Pipeline
//   Definition      : Pipeline script from SCM
//   SCM / Repository: https://github.com/Gaspezia/gaspezia-ci.git
//   Credentials     : github-gaspezia-stacks
//   Branch          : */main
//   Script Path     : jenkins/conformance-audit.Jenkinsfile
//   Lightweight checkout : coche
//   Puis LANCER UN BUILD A LA MAIN : Jenkins n'enregistre le `cron` declare dans
//   un Jenkinsfile qu'apres un premier build. Sans ce build d'amorce, l'audit ne
//   se declenchera jamais tout seul.
// =============================================================================
library identifier: 'gaspezia-ci@main', retriever: modernSCM([$class: 'GitSCMSource',
    remote: 'https://github.com/Gaspezia/gaspezia-ci.git', credentialsId: 'github-gaspezia-stacks'])

gaspeziaConformanceAudit()
