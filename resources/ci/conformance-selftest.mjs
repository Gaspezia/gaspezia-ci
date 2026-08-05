#!/usr/bin/env node
// =============================================================================
// Auto-test du verrou de conformite. Tourne AVANT l'audit, a chaque build.
// =============================================================================
// POURQUOI : un controle qui ne detecte plus rien est pire que pas de controle —
// il rassure. Les regles de conformance-check.mjs sont des expressions
// regulieres appliquees a des Jenkinsfile et des Dockerfile ; le jour ou la
// bibliotheque change la forme de son pod, ou ou un depot reformate son
// Dockerfile, une regle peut cesser de matcher SANS que rien ne devienne rouge.
// L'audit afficherait alors « 13/13 conformes » — un faux vert permanent.
//
// Cet auto-test fabrique deux depots factices : un CONFORME (qui doit passer) et
// un qui viole les SEPT regles a la fois (qui doit toutes les declencher). Il
// couvre aussi les deux pieges de commentaire : le Dockerfile du gabarit CITE
// `npm i -g pnpm` dans un commentaire pour expliquer pourquoi on ne le fait pas,
// et un Jenkinsfile migre cite `podTemplate` dans son en-tete. Une regle qui ne
// filtrerait pas les commentaires ferait echouer les depots CORRECTS.
//
// Il verifie enfin que le rapport reste POSTABLE sous charge : Discord ne
// tronque pas un embed trop gros, il REJETTE la requete (HTTP 400). Sans ce
// garde-fou on perdrait toute notification precisement le jour ou le parc est au
// plus mal, puisque le rapport le plus long est le plus alarmant.
//
// Il lance le vrai script en sous-processus (et non par import) : c'est la CLI
// reellement utilisee par le pipeline qui est testee, arguments compris.
// Exit 0 = les regles mordent encore ; exit 1 = le verrou est casse.
// =============================================================================
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, rmSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ICI = dirname(fileURLToPath(import.meta.url));
const SCRIPT = join(ICI, 'conformance-check.mjs');
const racine = mkdtempSync(join(tmpdir(), 'conformite-'));

const ecrire = (depot, fichiers) => {
  mkdirSync(join(racine, depot), { recursive: true });
  for (const [nom, contenu] of Object.entries(fichiers)) writeFileSync(join(racine, depot, nom), contenu);
};

// ---- depot CONFORME (avec les deux pieges de commentaire) --------------------
ecrire('depot-conforme', {
  Jenkinsfile: `// Tout le pipeline (pod d'agent k8s, kaniko, podTemplate, kind: Pod) vit
// desormais dans la bibliotheque partagee : gaspezia-ci.
library identifier: 'gaspezia-ci@main', retriever: modernSCM([$class: 'GitSCMSource',
    remote: 'https://github.com/Gaspezia/gaspezia-ci.git', credentialsId: 'github-gaspezia-stacks'])
gaspeziaNodeApi(imageName: 'depot-conforme')
`,
  'package.json': '{ "name": "depot-conforme", "packageManager": "pnpm@11.20.0" }\n',
  Dockerfile: `# corepack, et NON \`npm i -g pnpm\` : ce dernier installe la derniere version publiee.
FROM nexus.gaspezia.lan/docker-all/node:24-slim AS base
RUN corepack enable
FROM base AS deps
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile
FROM nexus.gaspezia.lan/docker-all/node:24-slim AS runtime
`,
  'pnpm-workspace.yaml': "allowBuilds:\n  '@prisma/engines': true\n",
});

// ---- depot qui viole les sept regles ----------------------------------------
ecrire('depot-casse', {
  Jenkinsfile: `pipeline {
  agent { kubernetes { cloud 'k8s'
      yaml '''
apiVersion: v1
kind: Pod
spec:
  containers:
    - name: node
      image: node:22-bookworm
      resources:
        limits:   { cpu: "3", memory: "2Gi" }
    - name: sonar-scanner
      resources:
        limits:   { cpu: "3", memory: "8Gi" }
'''
  } }
  stages { stage('x') { steps { echo 'x' } } }
}
`,
  'package.json': '{ "name": "depot-casse", "packageManager": "pnpm@10.23.0" }\n',
  Dockerfile: `FROM nexus.gaspezia.lan/docker-all/node:22-slim AS base
RUN npm i -g pnpm@10
FROM base AS deps
COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
`,
  'pnpm-workspace.yaml': 'allowBuilds:\n  esbuild: true\n',
});

// La bibliotheque, telle que le gabarit l'attend.
mkdirSync(join(racine, 'gaspezia-ci', 'vars'), { recursive: true });
for (const step of ['gaspeziaNodeApi', 'gaspeziaAngularWeb']) {
  writeFileSync(join(racine, 'gaspezia-ci', 'vars', `${step}.groovy`),
    "        nodeMemLimit  : r.nodeMemLimit  ?: '3Gi',\n");
}

const manifeste = join(racine, 'manifeste.json');
writeFileSync(manifeste, JSON.stringify({
  gabarit: {
    packageManager: 'pnpm@11.20.0',
    imagesNodeAutorisees: ['node:24-slim', 'node-gaspezia:24', 'node-gaspezia-migrate:24'],
    memoireMinConteneurNode: '3Gi',
    stepsBibliotheque: ['gaspeziaNodeApi', 'gaspeziaAngularWeb'],
  },
  depots: [
    { nom: 'depot-conforme', type: 'api', dispenses: {} },
    { nom: 'depot-casse', type: 'api', dispenses: {} },
  ],
}));

const run = spawnSync(process.execPath, [SCRIPT, '--racine', racine, '--manifeste', manifeste,
  '--sortie', join(racine, 'rapport.json')], { cwd: racine, encoding: 'utf8' });
const rapport = JSON.parse(spawnSync('cat', [join(racine, 'rapport.json')], { encoding: 'utf8' }).stdout);

const conforme = rapport.depots.find((d) => d.depot === 'depot-conforme');
const casse = rapport.depots.find((d) => d.depot === 'depot-casse');
const declenchees = new Set(casse.ecarts.map((e) => e.regle));
const attendues = ['jenkinsfile-bibliotheque', 'jenkinsfile-pod-partage', 'package-manager',
  'corepack', 'images-node', 'pnpm-workspace-copie', 'memoire-conteneur-node'];

const echecs = [];
if (run.status !== 1) echecs.push(`exit ${run.status} au lieu de 1 : une derive doit faire ECHOUER l'audit`);

// ---- le rapport Discord doit rester POSTABLE, surtout quand il est long ------
// Discord ne tronque pas un embed trop gros : il REJETTE la requete (HTTP 400).
// On perdrait donc toute notification precisement le jour ou le parc est au plus
// mal — le rapport le plus long est le plus alarmant. Mesure du 2026-08-05 sur
// l'etat reel du parc (12 depots en ecart) : 5635 caracteres pour 6000 permis.
// Ce cas de charge (30 depots en ecart) verifie que le budget tient quand meme.
{
  const stress = mkdtempSync(join(tmpdir(), 'conformite-charge-'));
  const noms = Array.from({ length: 30 }, (_, i) => `depot-casse-${i}`);
  for (const n of noms) {
    mkdirSync(join(stress, n), { recursive: true });
    for (const f of ['Jenkinsfile', 'package.json', 'Dockerfile', 'pnpm-workspace.yaml']) {
      writeFileSync(join(stress, n, f), readFileSync(join(racine, 'depot-casse', f)));
    }
  }
  const manifesteStress = join(stress, 'manifeste.json');
  writeFileSync(manifesteStress, JSON.stringify({
    gabarit: JSON.parse(readFileSync(manifeste, 'utf8')).gabarit,
    depots: noms.map((n) => ({ nom: n, type: 'api', dispenses: {} })),
  }));
  spawnSync(process.execPath, [SCRIPT, '--racine', stress, '--manifeste', manifesteStress,
    '--sortie', join(stress, 'rapport.json')], { cwd: stress, encoding: 'utf8' });

  const embed = JSON.parse(readFileSync(join(stress, 'discord-payload.json'), 'utf8')).embeds[0];
  const caracteres = embed.title.length + embed.description.length + embed.footer.text.length
    + embed.fields.reduce((s, f) => s + f.name.length + f.value.length, 0);
  if (caracteres > 6000) echecs.push(`embed Discord a ${caracteres} caracteres (limite 6000) : le webhook repondrait 400 et personne ne serait prevenu`);
  if (embed.fields.length > 25) echecs.push(`embed Discord a ${embed.fields.length} champs (limite 25)`);
  if (embed.fields.some((f) => f.value.length > 1024)) echecs.push('un champ Discord depasse 1024 caracteres');
  if (!embed.fields.some((f) => /de plus en écart/.test(f.name))) echecs.push('30 depots en ecart mais aucun champ ne dit combien ont ete omis : le rapport ment par omission');
  rmSync(stress, { recursive: true, force: true });
}

if (!conforme.conforme) echecs.push(`le depot conforme est declare en ecart : ${JSON.stringify(conforme.ecarts)}`);
for (const r of attendues) if (!declenchees.has(r)) echecs.push(`la regle \`${r}\` ne mord plus (aucun ecart sur un depot qui la viole)`);
if (rapport.bibliotheque.length) echecs.push(`faux positif sur les defauts de la bibliotheque : ${rapport.bibliotheque.join(' ; ')}`);

rmSync(racine, { recursive: true, force: true });

if (echecs.length) {
  console.error('[auto-test] LE VERROU EST CASSE :');
  for (const e of echecs) console.error(`  - ${e}`);
  console.error('\nLog du script audite :\n' + run.stdout);
  process.exit(1);
}
console.log(`[auto-test] OK — les ${attendues.length} regles mordent, et un depot conforme passe.`);
