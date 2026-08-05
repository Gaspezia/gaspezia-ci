# Le verrou anti-dérive — audit de conformité du parc au gabarit CI

> Ce document est volontairement séparé du `README.md` : deux PR ouvertes le
> réécrivent au moment où ce verrou est livré (#2 `gaspeziaNodeApi`, #3
> `gaspeziaAngularWeb`). Une ligne de renvoi depuis le README sera ajoutée une
> fois le train de merge passé.

## Le problème que ça résout

Au 2026-08-05, **13 dépôts applicatifs** (7 fronts Angular + 6 API Node)
portaient le **même** `Jenkinsfile` de ~270 lignes, recopié à la main. Personne
n'avait décidé ça : c'est ce que devient un gabarit que rien ne vérifie. État
réellement constaté ce jour-là, sur un parc pourtant censé être homogène :

| Dérive constatée | Conséquence |
|---|---|
| limite CPU `jnlp` à `500m` sur un dépôt, `2` sur les douze autres | builds deux fois plus lents, sans raison |
| un dépôt poussait le tag **mobile** (`dev`) là où les autres poussent le tag **immuable** (`dev-<sha>`) | traçabilité GitOps cassée **en silence** |
| un dépôt encore en `npm i -g pnpm` | version de pnpm différente à chaque build **et** différente de la CI |
| un dépôt laissé à `2Gi` sur le conteneur `node` | OOMKill (le pic mesuré est à 2043 Mi pour 2048 Mi de limite) |

Sortir le pipeline dans cette bibliothèque supprime la duplication. Mais **rien
n'empêche un dépôt d'en ressortir** : il suffit de recoller un vieux
`Jenkinsfile`. Sans contrôle, tout ce travail se re-disperse en six mois.

## La forme retenue : un job périodique qui notifie, pas une porte qui bloque

**Job Jenkins `ci-conformance`**, hebdomadaire, qui clone les 13 dépôts, les
confronte au gabarit, poste **un** rapport Discord et passe **rouge** en cas de
dérive.

Quatre raisons de préférer ça à une étape ajoutée au pipeline de chaque dépôt :

1. **Un dépôt qui dérive est exactement un dépôt que personne ne touche.** Une
   étape dans le pipeline applicatif ne s'exécute que si le dépôt est buildé :
   elle ne verra jamais le dépôt dormant, qui est le cas le plus fréquent.
2. **Un contrôle interne au pipeline est aveugle à sa propre absence.** La règle
   « le `Jenkinsfile` appelle la bibliothèque » ne *peut pas* être vérifiée par
   la bibliothèque : un dépôt qui ne l'appelle plus ne l'exécute plus. Seul un
   audit **externe** voit ça.
3. **Bloquer une PR sur un écart d'outillage se paie en contournements**, et le
   contrôle finit désactivé — donc ne verrouille plus rien. Ici l'échec est
   porté par un job d'infra que personne n'attend, pas par la PR d'un dev.
4. Le patron est **déjà éprouvé** dans le parc : `build-image-node-gaspezia`
   (gaspezia-build-config) tourne en cron avec la même notification Discord.

Le complément par build existe et utilise **le même moteur** — voir
[Retour immédiat au développeur](#retour-immédiat-au-développeur).

## Les sept règles

Le gabarit est de la **donnée** (`resources/ci/conformance-repos.json`), pas du
code : relever pnpm ou Node, c'est une ligne de JSON.

| Règle | Vérifie | Pourquoi |
|---|---|---|
| `jenkinsfile-bibliotheque` | le `Jenkinsfile` appelle `gaspeziaNodeApi()` ou `gaspeziaAngularWeb()` | sinon aucun correctif de la bibliothèque ne l'atteint |
| `jenkinsfile-pod-partage` | aucun `podTemplate(` / `agent kubernetes {` / `kind: Pod` local | un pod redéfini échappe au right-sizing et aux correctifs de ressources |
| `package-manager` | `package.json` déclare `packageManager: pnpm@11.20.0` | source unique de la version pnpm, lue par corepack |
| `corepack` | le `Dockerfile` fait `corepack enable` et **pas** `npm i -g pnpm` | `npm i -g pnpm` installe la *dernière* version publiée : différente à chaque build, différente de la CI |
| `images-node` | toute image Node est `node:24-slim` (ou `node-gaspezia[-migrate]:24`) | Node 24 = Active LTS (Krypton, avril 2028) ; **pas** Node 26, encore Current |
| `pnpm-workspace-copie` | si `pnpm-workspace.yaml` existe, il est `COPY` **avant** le `pnpm install` | piège réel : **CI verte, build d'image rouge** (`ERR_PNPM_IGNORED_BUILDS`) |
| `memoire-conteneur-node` | limite mémoire du conteneur `node` ≥ `3Gi` | pic mesuré à 2043 Mi pour 2048 Mi de limite → OOMKill du 2026-08-05 |

Plus une règle **hors dépôts** : les **valeurs par défaut de la bibliothèque
elle-même** sont vérifiées (`nodeMemLimit` dans `gaspeziaNodeApi.groovy` et
`gaspeziaAngularWeb.groovy`). Sans elle, abaisser ce défaut ferait retomber
**tous** les dépôts migrés sous le seuil sans qu'aucun ne soit signalé : un dépôt
conforme n'a, par construction, plus rien à inspecter chez lui. **Le verrou
surveille aussi la serrure.**

### Ce qui n'est délibérément pas contrôlé

- **Le conteneur `node` de l'agent Jenkins** est `node:22-bookworm` sur tout le
  parc, y compris dans la bibliothèque. C'est l'environnement de *lint/test*, pas
  l'image applicative ; l'aligner sur 24 est une décision à prendre à part, pas
  un effet de bord d'un audit. La règle `images-node` ne juge que les `FROM` du
  `Dockerfile`.
- **Le préfixe de registre** des images : seuls le nom et le tag comptent, les
  dépôts tirent via le miroir Nexus.
- **`gaspezia-auth`** (SDK npm, pas d'image) et **`Wiki`** (site retype, pas de
  `package.json`) sont hors périmètre, avec la raison inscrite dans le manifeste.

## Dispenses

Un écart connu et assumé se déclare dans le manifeste :

```json
{ "nom": "un-depot", "type": "api", "dispenses": { "images-node": "reste en node:22 jusqu'à la migration Prisma 7 — 2026-09" } }
```

L'écart reste **visible** dans le rapport mais ne fait plus échouer l'audit.
Volontairement nominative et en clair : une dispense doit se voir en relecture de
PR, sinon c'est une désactivation silencieuse du contrôle. **Aucune au
2026-08-05.**

## L'auto-test : pourquoi le verrou se teste lui-même

Un contrôle qui ne détecte plus rien est **pire** que pas de contrôle : il
rassure. Les règles sont des expressions régulières sur des `Jenkinsfile` et des
`Dockerfile` ; le jour où la bibliothèque change la forme de son pod, une règle
peut cesser de matcher **sans que rien ne devienne rouge** — l'audit afficherait
alors « 13/13 conformes », un faux vert permanent.

`resources/ci/conformance-selftest.mjs` fabrique deux dépôts factices — un
conforme, un qui viole les **sept** règles — et vérifie que les sept mordent et
que le conforme passe. Il couvre aussi les deux **pièges de commentaire** : le
`Dockerfile` du gabarit *cite* `npm i -g pnpm` dans un commentaire pour expliquer
pourquoi on ne le fait pas, et un `Jenkinsfile` migré cite `podTemplate` dans son
en-tête. Une règle qui ne filtrerait pas les commentaires ferait échouer les
dépôts **corrects**.

Il tourne **avant** l'audit, à chaque build. S'il échoue, l'audit ne tourne pas
et Discord reçoit « l'audit n'a pas pu s'exécuter » — parce que **l'absence de
rapport n'est pas un parc conforme**.

## Retour immédiat au développeur

Le même moteur audite **un seul** dépôt déjà checkouté :

```sh
node conformance-check.mjs --depot . --nom mon-depot
```

À câbler dans `gaspeziaNodeApi` / `gaspeziaAngularWeb` **une fois #2 et #3
mergées**, dans le stage `Quality (CI)`, en **non bloquant** :

```groovy
container('node') {
  writeFile file: '.conformance-check.mjs',  text: libraryResource('ci/conformance-check.mjs')
  writeFile file: '.conformance-repos.json', text: libraryResource('ci/conformance-repos.json')
  // Le nom vient du remote git, pas du répertoire de travail (qui porte le nom
  // du job) ni de `imageName` (qui en diffère : image `gaspezia-email-sender-api`
  // pour le dépôt `email-sender-api`). Sans le bon nom, les dispenses du
  // manifeste ne s'appliquent pas.
  // returnStatus : informatif. Un écart d'outillage ne fait pas échouer la PR d'un dev.
  sh(script: '''node .conformance-check.mjs --depot . \
       --nom "$(basename -s .git "$(git config --get remote.origin.url)")"''', returnStatus: true)
}
```

Volontairement **non livré** dans la même PR : `gaspeziaNodeApi.groovy` et
`gaspeziaAngularWeb.groovy` n'existent pas encore sur `main`, et le câblage doit
se faire *après* le train de merge, pas au prix d'un conflit sur les deux PR en
cours.

## Exploitation

| | |
|---|---|
| Job | `ci-conformance` — Pipeline script from SCM, `jenkins/conformance-audit.Jenkinsfile` |
| Fréquence | `H 5 * * 1` (lundi tôt) — **déclarée dans la bibliothèque**, pas paramétrable par l'appelant |
| Notification | webhook `discord-webhook`, un message unique, dépôts conformes non listés |
| Rouge | dérive détectée (exit 1) **ou** audit inexécutable (exit ≠ 0/1) |
| Artefact | `conformance-report.json` (rapport machine, archivé sur le build) |
| Branche auditée | la **branche par défaut** de chaque dépôt — `main` sur 3, `dev` sur 10 ; c'est celle que voit un humain sur GitHub |
| Traçabilité | chaque dépôt est rapporté avec `branche@sha` : un rapport d'il y a une semaine reste interprétable |
| Credentials | `github-gaspezia-stacks` (clone), `discord-webhook` |

> ⚠️ Après création du job, **lancer un build à la main** : Jenkins n'enregistre
> le `cron` déclaré dans un `Jenkinsfile` qu'après un premier build.

## À quoi s'attendre au premier build

**Rouge, et c'est l'état réel du parc** — pas un défaut de l'audit. Au 2026-08-05,
un seul dépôt (`dorangeonTraiteur`, pilote Angular) appelle la bibliothèque, et
les steps `gaspeziaNodeApi` / `gaspeziaAngularWeb` ne sont pas encore sur `main`.
Mesure faite ce jour-là sur les branches par défaut : **1/13 conformes**, dont
`bot-twitch-api` qui cumule cinq écarts (pipeline recopié, pod local,
`pnpm@10.23.0`, `npm i -g pnpm`, conteneur `node` à 2Gi).

Le vert viendra du train de merge : #2, #3, puis les PR de migration de chaque
dépôt. **C'est précisément ce que l'audit doit mesurer** — un verrou qu'on livre
déjà vert ne prouve rien.

## Ajouter un dépôt au parc

L'ajouter à `depots` dans `resources/ci/conformance-repos.json`. **Un dépôt
applicatif absent du manifeste n'est audité par personne** — c'est le seul angle
mort par construction de ce dispositif.
