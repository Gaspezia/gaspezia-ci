#!/usr/bin/env node
// Poste le résultat de l'analyse SonarQube sur Discord — INFORMATIF, ne bloque JAMAIS (exit 0).
// Env : SONAR_TOKEN, DISCORD_WEBHOOK (requis) ; BRANCH_NAME, BUILD_URL (optionnels). Node >= 20 (fetch natif).
import { readFileSync } from 'node:fs';

const soft = (msg) => { console.error(`[sonar-discord] ${msg}`); process.exit(0); }; // exit 0 = jamais bloquant
const TOKEN = process.env.SONAR_TOKEN, WEBHOOK = process.env.DISCORD_WEBHOOK;
const BRANCH = process.env.BRANCH_NAME || 'dev';
if (!TOKEN || !WEBHOOK) soft('SONAR_TOKEN ou DISCORD_WEBHOOK manquant');

let report;
try {
  report = Object.fromEntries(readFileSync(process.env.REPORT_TASK || '.scannerwork/report-task.txt', 'utf8')
    .split('\n').filter(Boolean).map(l => { const i = l.indexOf('='); return [l.slice(0, i), l.slice(i + 1)]; }));
} catch { soft('.scannerwork/report-task.txt introuvable (le scan a-t-il tourné ?)'); }

const { serverUrl, ceTaskUrl, dashboardUrl, projectKey } = report;
const auth = { Authorization: 'Basic ' + Buffer.from(`${TOKEN}:`).toString('base64') };
const api = async (u) => { const r = await fetch(u, { headers: auth }); if (!r.ok) throw new Error(`${u} -> HTTP ${r.status}`); return r.json(); };
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

try {
  // attendre la fin de la tâche Compute Engine (max ~2 min)
  let analysisId, status = 'PENDING';
  for (let i = 0; i < 40; i++) {
    const t = (await api(ceTaskUrl)).task; status = t.status;
    if (['SUCCESS', 'FAILED', 'CANCELED'].includes(status)) { analysisId = t.analysisId; break; }
    await sleep(3000);
  }
  if (status !== 'SUCCESS') soft(`tâche CE = ${status}`);

  const gate = (await api(`${serverUrl}/api/qualitygates/project_status?analysisId=${analysisId}`)).projectStatus.status; // OK|ERROR|WARN|NONE
  const keys = 'coverage,duplicated_lines_density,bugs,vulnerabilities,code_smells,ncloc,new_bugs,new_vulnerabilities,new_code_smells';
  const measures = (await api(`${serverUrl}/api/measures/component?component=${projectKey}&metricKeys=${keys}`)).component.measures || [];
  const m = Object.fromEntries(measures.map(x => [x.metric, x.period?.value ?? x.value]));
  const v = (k, s = '') => (m[k] !== undefined ? `${m[k]}${s}` : 'n/a');

  const icon = { OK: '🟢', ERROR: '🔴', WARN: '🟡' }[gate] || 'ℹ️';
  const color = { OK: 3066993, ERROR: 15158332, WARN: 16776960 }[gate] || 9807270;
  const body = JSON.stringify({
    username: 'SonarQube',
    embeds: [{
      title: `📊 SonarQube — ${projectKey} (${BRANCH})   ${icon} ${gate} (info)`,
      url: dashboardUrl, color,
      fields: [
        { name: 'Couverture', value: v('coverage', '%'), inline: true },
        { name: 'Duplications', value: v('duplicated_lines_density', '%'), inline: true },
        { name: 'Lignes', value: v('ncloc'), inline: true },
        { name: '🐛 Bugs', value: `${v('bugs')} (new: ${v('new_bugs')})`, inline: true },
        { name: '🔒 Vulns', value: `${v('vulnerabilities')} (new: ${v('new_vulnerabilities')})`, inline: true },
        { name: '💨 Code smells', value: `${v('code_smells')} (new: ${v('new_code_smells')})`, inline: true },
      ],
      footer: { text: 'Analyse informative — ne bloque jamais le pipeline' },
    }],
  });
  const r = await fetch(WEBHOOK, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body });
  console.log(`[sonar-discord] Discord POST -> HTTP ${r.status}`);
} catch (e) { soft(`erreur: ${e.message}`); }
