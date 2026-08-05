#!/usr/bin/env node
// =============================================================================
// Verrou anti-derive : audit de conformite du parc applicatif au gabarit de CI.
// =============================================================================
// POURQUOI CE FICHIER EXISTE
// Au 2026-08-05, sept fronts Angular et six API Node portaient le MEME Jenkinsfile
// de ~270 lignes, recopie a la main. Personne n'avait decide ca : c'est ce que
// devient un gabarit que rien ne verifie. Etat constate ce jour-la, sur un parc
// pourtant cense etre homogene :
//   - limite CPU `jnlp` a 500m sur un depot, a 2 sur les douze autres ;
//   - un depot poussait dans gaspezia-stacks le tag MOBILE (`dev`) la ou les
//     autres poussent le tag IMMUABLE (`dev-<sha>`) — tracabilite GitOps cassee
//     en silence ;
//   - un depot encore en `npm i -g pnpm`, donc une version de pnpm differente a
//     chaque build et differente de celle de la CI ;
//   - un depot laisse a 2Gi sur le conteneur `node` apres l'OOMKill qui avait
//     fait passer tous les autres a 3Gi.
//
// Sortir le pipeline dans cette bibliotheque supprime la duplication, mais RIEN
// n'empeche un depot d'en ressortir : il suffit de recoller un vieux Jenkinsfile.
// Ce script est le verrou. Il relit le parc depuis GitHub et ECHOUE (exit 1) des
// qu'un depot s'ecarte du gabarit.
//
// CE QU'IL NE FAIT PAS, VOLONTAIREMENT : il ne corrige rien, n'ouvre aucune PR,
// ne touche a aucun depot. Il lit, il compare, il rapporte. Et il ne s'invite
// PAS dans la CI quotidienne des developpeurs : un controle qui bloque une PR
// pour un ecart d'outillage finit desactive, donc ne verrouille plus rien.
//
// Le gabarit lui-meme est de la DONNEE (conformance-repos.json) : relever pnpm
// ou Node, c'est une ligne de JSON, pas une relecture de regex.
//
// USAGE
//   node conformance-check.mjs --liste
//       -> ecrit sur stdout, un par ligne, les depots a cloner (parc + la
//          bibliotheque elle-meme). Evite au pipeline Groovy de parser du JSON.
//   node conformance-check.mjs --racine <dir> [--manifeste f] [--sortie f]
//       -> audite tout le parc, <dir> contenant un clone par depot (nom du
//          dossier = `nom` du manifeste). Ecrit le rapport JSON + la charge
//          utile Discord. Exit 1 si derive.
//   node conformance-check.mjs --depot <dir> [--nom n]
//       -> audite UN depot deja checkout (usage build-local, non bloquant cote
//          appelant). Pas de charge utile Discord.
//   Exit : 0 conforme, 1 derive, 2 erreur d'execution.
// =============================================================================
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { join, dirname, basename, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ICI = dirname(fileURLToPath(import.meta.url));

const arg = (nom, defaut) => {
  const i = process.argv.indexOf(`--${nom}`);
  return i !== -1 && process.argv[i + 1] && !process.argv[i + 1].startsWith('--')
    ? process.argv[i + 1] : defaut;
};
const drapeau = (nom) => process.argv.includes(`--${nom}`);

const lire = (f) => (existsSync(f) ? readFileSync(f, 'utf8') : null);

// -----------------------------------------------------------------------------
// Nettoyage prealable : ne juger que du CODE, jamais des commentaires.
// -----------------------------------------------------------------------------
// Sans ca, un depot serait declare conforme parce que le mot `gaspeziaNodeApi`
// apparait dans un commentaire d'en-tete — exactement le faux vert qui rendrait
// ce controle inutile. Le `[^:]` protege les `https://` : sinon toute URL du
// Jenkinsfile serait tronquee et le fichier deviendrait illisible.
const sansCommentairesGroovy = (t) =>
  t.replace(/\/\*[\s\S]*?\*\//g, '\n')
    .split('\n').map((l) => l.replace(/(^|[^:])\/\/.*$/, '$1')).join('\n');

// Symetrique cote Dockerfile : le gabarit documente `corepack enable` par un
// commentaire qui CITE `npm i -g pnpm` comme contre-exemple. Chercher sans
// filtrer les `#` ferait echouer precisement les depots corrects.
const instructionsDockerfile = (t) =>
  t.split('\n').filter((l) => !/^\s*#/.test(l) && l.trim() !== '');

// -----------------------------------------------------------------------------
// Quantites memoire k8s
// -----------------------------------------------------------------------------
const UNITES = { Ki: 1024, Mi: 1024 ** 2, Gi: 1024 ** 3, K: 1e3, M: 1e6, G: 1e9 };
const octets = (q) => {
  const m = /^(\d+(?:\.\d+)?)\s*(Ki|Mi|Gi|K|M|G)?$/.exec(String(q).trim());
  return m ? parseFloat(m[1]) * (m[2] ? UNITES[m[2]] : 1) : null;
};

// Limite memoire du conteneur `node` quand le pod est encore INLINE dans le
// Jenkinsfile (depot non migre). On s'arrete au conteneur suivant, sinon on
// lirait les limites de `sonar-scanner`.
const memoireNodeInline = (jf) => {
  const lignes = jf.split('\n');
  const debut = lignes.findIndex((l) => /^\s*-\s*name:\s*node\s*$/.test(l));
  if (debut === -1) return null;
  for (let i = debut + 1; i < lignes.length; i++) {
    if (/^\s*-\s*name:\s*\S+/.test(lignes[i])) break;
    const m = /limits:\s*\{[^}]*memory:\s*"?([^",}]+)"?/.exec(lignes[i]);
    if (m) return m[1].trim();
  }
  return null;
};

// -----------------------------------------------------------------------------
// Les regles. Chacune rend null (conforme) ou un message DESTINE A UN HUMAIN :
// il doit dire quoi corriger et pourquoi la regle existe, pas seulement qu'un
// truc ne va pas — c'est ce texte qui atterrit sur Discord.
// -----------------------------------------------------------------------------
const REGLES = [
  {
    id: 'jenkinsfile-bibliotheque',
    titre: 'Le Jenkinsfile delegue a la bibliotheque partagee',
    verifie: ({ jenkinsfile, gabarit }) => {
      if (jenkinsfile == null) return 'Jenkinsfile introuvable';
      const code = sansCommentairesGroovy(jenkinsfile);
      const appel = gabarit.stepsBibliotheque.find((s) => new RegExp(`\\b${s}\\s*\\(`).test(code));
      return appel ? null
        : `n'appelle ni ${gabarit.stepsBibliotheque.join('() ni ')}() : pipeline recopie a la main, il ne recevra aucun correctif de la bibliotheque`;
    },
  },
  {
    id: 'jenkinsfile-pod-partage',
    titre: "Aucun pod d'agent redefini localement",
    verifie: ({ jenkinsfile }) => {
      if (jenkinsfile == null) return 'Jenkinsfile introuvable';
      const code = sansCommentairesGroovy(jenkinsfile);
      const traces = [];
      if (/\bpodTemplate\s*\(/.test(code)) traces.push('podTemplate(');
      if (/\bkubernetes\s*\{/.test(code)) traces.push('agent kubernetes { yaml ... }');
      if (/kind:\s*Pod/.test(code)) traces.push('kind: Pod');
      return traces.length === 0 ? null
        : `redefinit son propre pod d'agent (${traces.join(', ')}) : le right-sizing et les correctifs de ressources faits dans la bibliotheque ne l'atteindront pas`;
    },
  },
  {
    id: 'package-manager',
    titre: 'package.json declare le pnpm du parc',
    verifie: ({ packageJson, gabarit }) => {
      if (packageJson == null) return 'package.json introuvable';
      let pm;
      try { pm = JSON.parse(packageJson).packageManager; }
      catch (e) { return `package.json illisible (${e.message})`; }
      if (!pm) return `champ "packageManager" absent (attendu "${gabarit.packageManager}") : corepack ne sait pas quelle version installer, elle redevient flottante`;
      return pm === gabarit.packageManager ? null
        : `packageManager = "${pm}", attendu "${gabarit.packageManager}"`;
    },
  },
  {
    id: 'corepack',
    titre: 'pnpm vient de corepack, jamais de `npm i -g pnpm`',
    verifie: ({ dockerfile }) => {
      if (dockerfile == null) return 'Dockerfile introuvable';
      const inst = instructionsDockerfile(dockerfile);
      const ecarts = [];
      const globale = inst.filter((l) => /npm\s+(i|install|add)\s+(-g|--global)\s+[^\n]*\bpnpm\b/.test(l));
      if (globale.length) {
        ecarts.push(`installe pnpm globalement (${globale.length} instruction(s) \`npm i -g pnpm\`) : ca installe la DERNIERE version publiee, donc une version differente a chaque build et differente de celle de la CI`);
      }
      if (!inst.some((l) => /corepack\s+enable/.test(l))) {
        ecarts.push('aucun `corepack enable` : la version de pnpm ne vient pas du champ packageManager');
      }
      return ecarts.length ? ecarts.join(' ; ') : null;
    },
  },
  {
    id: 'images-node',
    titre: 'Images Node du gabarit',
    verifie: ({ dockerfile, gabarit }) => {
      if (dockerfile == null) return 'Dockerfile introuvable';
      // On ne juge QUE les images Node. Le runtime des fronts est
      // nginx-unprivileged : hors sujet, et le prefixe de registre est libre.
      const fautives = [];
      for (const l of instructionsDockerfile(dockerfile)) {
        const m = /^\s*FROM\s+(\S+)/i.exec(l);
        if (!m) continue;
        const nomTag = m[1].split('/').pop();
        if (!/^node(-gaspezia(-migrate)?)?:/.test(nomTag)) continue;
        if (!gabarit.imagesNodeAutorisees.includes(nomTag)) fautives.push(nomTag);
      }
      return fautives.length === 0 ? null
        : `image(s) Node hors gabarit : ${[...new Set(fautives)].join(', ')} (attendu : ${gabarit.imagesNodeAutorisees.join(' | ')})`;
    },
  },
  {
    id: 'pnpm-workspace-copie',
    titre: 'pnpm-workspace.yaml copie AVANT le `pnpm install`',
    // PIEGE REEL du 2026-08-05, et le pire des cas : la CI passe (elle installe
    // depuis le depot complet) pendant que le build d'image echoue (l'etage
    // `deps` ne copie que package.json + lock). Depuis pnpm 10.23 ce fichier
    // porte les decisions `allowBuilds` ; pnpm 11 ne se contente plus d'un
    // avertissement, il fait ECHOUER l'install (ERR_PNPM_IGNORED_BUILDS).
    verifie: ({ dockerfile, workspace }) => {
      if (workspace == null) return null;              // pas de fichier, pas de piege
      if (dockerfile == null) return 'Dockerfile introuvable';
      const inst = instructionsDockerfile(dockerfile);
      const iCopie = inst.findIndex((l) =>
        /^\s*COPY\b/i.test(l) && (/pnpm-workspace\.yaml/.test(l) || /^\s*COPY\s+\.\s/i.test(l)));
      const iInstall = inst.findIndex((l) => /^\s*RUN\b[\s\S]*pnpm\s+install/i.test(l));
      if (iInstall === -1) return null;                // aucune install dans l'image
      if (iCopie === -1) return "pnpm-workspace.yaml existe mais n'est jamais COPY dans le Dockerfile : CI verte et build d'image rouge (ERR_PNPM_IGNORED_BUILDS)";
      return iCopie < iInstall ? null
        : 'pnpm-workspace.yaml est COPY APRES le `pnpm install` qui en a besoin : sans effet, l\'install echouera quand meme';
    },
  },
  {
    id: 'memoire-conteneur-node',
    titre: "Conteneur `node` de l'agent a >= 3Gi",
    // 3Gi et non 2Gi : mesure Prometheus sur 7 j, pic reel a 2043 Mi pour une
    // limite de 2048 Mi. Le conteneur touchait son plafond -> OOMKill le
    // 2026-08-05. La `request` n'est pas concernee : k8s ordonnance dessus, la
    // limite ne reserve rien.
    verifie: ({ jenkinsfile, gabarit }) => {
      if (jenkinsfile == null) return 'Jenkinsfile introuvable';
      const code = sansCommentairesGroovy(jenkinsfile);
      const mini = octets(gabarit.memoireMinConteneurNode);
      const surcharge = /nodeMemLimit\s*:\s*['"]([^'"]+)['"]/.exec(code);
      let valeur, origine;
      if (surcharge) { valeur = surcharge[1]; origine = 'surcharge du Jenkinsfile'; }
      else {
        const inline = memoireNodeInline(code);
        if (!inline) return null;   // ni surcharge ni pod inline : la valeur vient du
        valeur = inline;            // defaut de la bibliotheque, verifie par la regle
        origine = 'pod inline du Jenkinsfile';  // `bibliotheque-defauts` ci-dessous.
      }
      const o = octets(valeur);
      if (o == null) return `limite memoire du conteneur node illisible ("${valeur}")`;
      return o >= mini ? null
        : `conteneur node plafonne a ${valeur} (${origine}), minimum ${gabarit.memoireMinConteneurNode} — OOMKill mesure a 2Gi le 2026-08-05`;
    },
  },
];

// -----------------------------------------------------------------------------
// Regle a part : les DEFAUTS de la bibliotheque elle-meme.
// -----------------------------------------------------------------------------
// Sans elle, abaisser le defaut `nodeMemLimit` dans gaspezia-ci ferait retomber
// TOUS les depots migres sous le seuil sans qu'aucun ne soit signale : un depot
// conforme n'a, par construction, plus rien a inspecter chez lui. Le verrou doit
// donc aussi surveiller la serrure.
const verifieBibliotheque = (racine, gabarit) => {
  const ecarts = [];
  const mini = octets(gabarit.memoireMinConteneurNode);
  for (const step of gabarit.stepsBibliotheque) {
    const src = lire(join(racine, 'gaspezia-ci', 'vars', `${step}.groovy`));
    if (src == null) {
      ecarts.push(`${step}.groovy absent de gaspezia-ci@main : le step attendu par le gabarit n'est pas (encore) livre`);
      continue;
    }
    const m = /nodeMemLimit\s*:\s*r\.nodeMemLimit\s*\?:\s*['"]([^'"]+)['"]/.exec(src);
    if (!m) { ecarts.push(`${step}.groovy : defaut nodeMemLimit introuvable — le pod a-t-il change de forme ? (regle a reajuster)`); continue; }
    const o = octets(m[1]);
    if (o == null || o < mini) ecarts.push(`${step}.groovy : defaut nodeMemLimit = ${m[1]}, minimum ${gabarit.memoireMinConteneurNode}`);
  }
  return ecarts;
};

// -----------------------------------------------------------------------------
// Audit d'un depot
// -----------------------------------------------------------------------------
const auditeDepot = (dir, depot, gabarit) => {
  if (!existsSync(dir)) {
    return { depot: depot.nom, type: depot.type, ref: null, conforme: false,
             ecarts: [{ regle: 'clone', message: 'depot non clone : renomme, supprime, ou droits du token insuffisants' }] };
  }
  const contexte = {
    gabarit,
    jenkinsfile: lire(join(dir, 'Jenkinsfile')),
    dockerfile: lire(join(dir, depot.dockerfile || 'Dockerfile')),
    packageJson: lire(join(dir, 'package.json')),
    workspace: lire(join(dir, 'pnpm-workspace.yaml')),
  };
  const ecarts = [];
  for (const regle of REGLES) {
    let message;
    // Une regle qui plante ne doit jamais faire passer un depot pour conforme :
    // l'exception devient elle-meme un ecart.
    try { message = regle.verifie(contexte); }
    catch (e) { message = `regle en erreur : ${e.message}`; }
    if (!message) continue;
    const dispense = (depot.dispenses || {})[regle.id];
    ecarts.push(dispense ? { regle: regle.id, message, dispense } : { regle: regle.id, message });
  }
  return {
    depot: depot.nom,
    type: depot.type,
    // Ecrit par le pipeline apres le clone : tracer CE QUI a ete audite (branche
    // + sha) evite les debats sur un rapport vieux d'une semaine.
    ref: (lire(join(dir, '.audit-ref')) || '').trim() || null,
    conforme: ecarts.every((e) => e.dispense),
    ecarts,
  };
};

// -----------------------------------------------------------------------------
// Programme
// -----------------------------------------------------------------------------
const MANIFESTE = arg('manifeste', join(ICI, 'conformance-repos.json'));
let manifeste;
try { manifeste = JSON.parse(readFileSync(MANIFESTE, 'utf8')); }
catch (e) { console.error(`[conformite] manifeste illisible (${MANIFESTE}) : ${e.message}`); process.exit(2); }
const { gabarit, depots } = manifeste;

// --liste : ce que le pipeline doit cloner. La bibliotheque est du lot, ses
// defauts font partie du gabarit.
if (drapeau('liste')) {
  console.log([...depots.map((d) => d.nom), 'gaspezia-ci'].join('\n'));
  process.exit(0);
}

// --depot : un seul depot deja checkout (usage build-local).
if (drapeau('depot')) {
  const dir = resolve(arg('depot', '.'));
  const nom = arg('nom', basename(dir));
  const depot = depots.find((d) => d.nom === nom) || { nom, type: 'inconnu', dispenses: {} };
  if (!depots.some((d) => d.nom === nom)) {
    console.log(`[conformite] ${nom} n'est pas au manifeste : audit sans dispense. Un depot applicatif absent du manifeste n'est audite par personne — ajoute-le a conformance-repos.json.`);
  }
  const r = auditeDepot(dir, depot, gabarit);
  console.log(`\n=== Conformite au gabarit CI — ${r.depot} : ${r.conforme ? 'CONFORME' : 'ECART'} ===`);
  for (const e of r.ecarts) console.log(`  - [${e.regle}] ${e.message}${e.dispense ? `  (DISPENSE : ${e.dispense})` : ''}`);
  process.exit(r.conforme ? 0 : 1);
}

// --racine : le parc entier.
const RACINE = arg('racine', '.');
const SORTIE = arg('sortie', 'conformance-report.json');
const resultats = depots.map((d) => auditeDepot(join(RACINE, d.nom), d, gabarit));
const ecartsBibliotheque = verifieBibliotheque(RACINE, gabarit);
const conformes = resultats.filter((r) => r.conforme).length;

console.log(`\n=== Conformite du parc au gabarit CI Gaspezia — ${conformes}/${resultats.length} depots conformes ===\n`);
for (const r of resultats) {
  console.log(`${r.conforme ? 'OK   ' : 'ECART'}  ${r.depot} (${r.type})${r.ref ? `  [${r.ref}]` : ''}`);
  for (const e of r.ecarts) console.log(`         - [${e.regle}] ${e.message}${e.dispense ? `  (DISPENSE : ${e.dispense})` : ''}`);
}
if (ecartsBibliotheque.length) {
  console.log('\nECART  gaspezia-ci (bibliotheque partagee)');
  for (const e of ecartsBibliotheque) console.log(`         - ${e}`);
}

writeFileSync(SORTIE, JSON.stringify({
  date: new Date().toISOString(),
  gabarit, total: resultats.length, conformes,
  depots: resultats, bibliotheque: ecartsBibliotheque,
}, null, 2));

// -----------------------------------------------------------------------------
// Charge utile Discord : un seul message, lisible sur telephone. Le compteur
// d'abord, puis un champ par depot en ecart. Les depots CONFORMES ne sont pas
// listes : un rapport qu'on ne lit plus parce qu'il est trop long ne verrouille
// plus rien.
// -----------------------------------------------------------------------------
const enEcart = resultats.filter((r) => !r.conforme);
const derive = enEcart.length > 0 || ecartsBibliotheque.length > 0;
const tronque = (s, n) => (s.length > n ? `${s.slice(0, n - 1)}…` : s);

const titre = `${derive ? '❌' : '✅'} Conformité du parc au gabarit CI — ${conformes}/${resultats.length} dépôts conformes`;
const description = derive
  ? "Un dépôt **s'écarte du gabarit partagé**. Rien n'est bloqué côté développeurs : ce rapport est le seul signal."
  : `Les ${resultats.length} dépôts applicatifs suivent le gabarit partagé (bibliothèque \`gaspezia-ci\`, pnpm, Node, mémoire de l'agent).`;
const pied = 'Audit périodique — informatif pour les devs, rouge pour la CI';

// La bibliotheque d'abord (elle concerne TOUS les depots migres), puis les
// depots. Chaque valeur est bornee a 1024, limite d'un champ Discord.
const details = [
  ...(ecartsBibliotheque.length
    ? [{ name: '❌ gaspezia-ci (bibliothèque)', value: tronque(ecartsBibliotheque.map((e) => `• ${e}`).join('\n'), 1024) }]
    : []),
  ...enEcart.map((r) => ({
    name: `❌ ${r.depot}`,
    value: tronque(r.ecarts.filter((e) => !e.dispense).map((e) => `• \`${e.regle}\` : ${e.message}`).join('\n'), 1024),
  })),
];

// Discord refuse un embed de plus de 6000 caracteres au total (titre +
// description + noms et valeurs des champs + footer) et de plus de 25 champs.
// Au-dela, il ne TRONQUE pas : il REJETTE la requete (HTTP 400). On perdrait
// donc toute notification precisement le jour ou le parc est au plus mal — le
// rapport le plus long est le plus alarmant. Mesure du 2026-08-05 sur l'etat
// reel (12 depots + la bibliotheque en ecart) : 5635 caracteres, soit 94 % du
// plafond -- il ne restait presque plus de marge. On coupe donc a 5800 (200
// caracteres de reserve sous la limite dure), en disant explicitement ce qui a
// saute plutot qu'en laissant croire que le parc va mieux qu'il ne va.
const PLAFOND = 5800;
let omis = 0;
const resume = () => (omis === 0 ? [] : [{
  name: `… et ${omis} dépôt(s) de plus en écart`,
  value: 'Détail non tenu dans un message Discord. Rapport complet : log du build et artefact `conformance-report.json`.',
}]);
const taille = () => titre.length + description.length + pied.length
  + [...details, ...resume()].reduce((s, f) => s + f.name.length + f.value.length, 0);
// On garde toujours au moins un champ detaille : un rapport qui ne nomme aucun
// depot n'aiderait personne.
while (details.length > 1 && (taille() > PLAFOND || details.length + resume().length > 25)) {
  details.pop();
  omis += 1;
}
const champs = [...details, ...resume()];

writeFileSync('discord-payload.json', JSON.stringify({
  username: 'Conformité CI',
  embeds: [{
    title: titre,
    description,
    url: process.env.BUILD_URL,
    color: derive ? 15158332 : 3066993,
    fields: champs,
    footer: { text: pied },
  }],
}));

process.exit(derive ? 1 : 0);
