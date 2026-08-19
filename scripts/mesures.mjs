#!/usr/bin/env node
//
// Measures the running stack while scripts/charge.sh's fleet is circulating.
// Phase 9 asks for four numbers; this produces three of them and says where the
// fourth comes from.
//
//   SSE fan-out delay     measured here, client-side
//   dashboard query times measured here, client-side
//   JVM memory            read here from /actuator/metrics (metrologie profile)
//   ingest p50/p95        logged by the simulator (JournalLatence), not here --
//                         see the note printed at the end
//
// Node rather than a shell pipeline because jq is not installed on this machine
// and because parsing an SSE stream in bash is not worth anyone's afternoon.
// No dependencies: Node 22 has fetch, streams and a JSON parser.
//
// Every subject is derived at run time. Phase 9 opens by saying that three
// acceptance commands across this project measured nothing because they named a
// row that could not exercise the behaviour, so this script hardcodes no ligne
// id, no gare id and no course id.
//
// Usage:
//   node scripts/mesures.mjs
//   node scripts/mesures.mjs --duree=180
//   TRINO_API_BASE_URL=http://localhost:8081 node scripts/mesures.mjs
//
// Environment:
//   TRINO_API_BASE_URL   default http://localhost:8080; 8081 on this dev machine
//   TRINO_MDP_RESPONSABLE default Trino2026!

const BASE = process.env.TRINO_API_BASE_URL ?? 'http://localhost:8080';
const COMPTE = 'responsable@sncft.tn';
const MOT_DE_PASSE = process.env.TRINO_MDP_RESPONSABLE ?? 'Trino2026!';

const options = Object.fromEntries(
  process.argv.slice(2)
    .filter((a) => a.startsWith('--'))
    .map((a) => a.slice(2).split('=')),
);
const DUREE_S = Number(options.duree ?? 120);

// A tick publishes its whole batch in one burst. Anything arriving more than
// this long after the previous frame starts a new burst. The simulator ticks
// every 5 s, so 1500 ms separates bursts comfortably while staying far above
// the spread within one.
const SEUIL_RAFALE_MS = 1500;

// ---------------------------------------------------------------- utilities

function centile(valeurs, part) {
  if (valeurs.length === 0) return null;
  const tries = [...valeurs].sort((a, b) => a - b);
  const rang = Math.ceil(part * tries.length) - 1;
  return tries[Math.min(Math.max(rang, 0), tries.length - 1)];
}

function ms(valeur) {
  return valeur === null ? '—' : `${Math.round(valeur)} ms`;
}

function patienter(secondes) {
  return new Promise((resoudre) => setTimeout(resoudre, secondes * 1000));
}

async function json(chemin, entetes = {}) {
  const reponse = await fetch(`${BASE}${chemin}`, { headers: entetes });
  if (!reponse.ok) {
    throw new Error(`GET ${chemin} → HTTP ${reponse.status}`);
  }
  return reponse.json();
}

// --------------------------------------------------------------- discovery

/** Active ligne ids, read from the public référentiel. Never a literal. */
async function lignesActives() {
  const page = await json('/api/v1/lignes?taille=100');
  const contenu = page.contenu ?? page;
  const ids = contenu.filter((l) => l.actif !== false).map((l) => l.id);
  if (ids.length === 0) {
    throw new Error('Aucune ligne active : rien à écouter.');
  }
  return ids;
}

async function jetonResponsable() {
  const reponse = await fetch(`${BASE}/api/v1/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: COMPTE, motDePasse: MOT_DE_PASSE }),
  });
  if (!reponse.ok) {
    throw new Error(`Connexion ${COMPTE} → HTTP ${reponse.status}`);
  }
  return (await reponse.json()).accessToken;
}

// ------------------------------------------------------------- SSE fan-out

/**
 * Holds one multiplexed stream open for the whole window and records the
 * arrival time of every frame.
 *
 * One connection carrying every ligne, which is what the portal opens since
 * phase 5 -- measuring five single-channel connections would measure a shape no
 * client uses.
 */
async function ecouterFlux(lignes, signal) {
  const url = `${BASE}/api/v1/stream?lignes=${lignes.join(',')}`;
  const reponse = await fetch(url, { headers: { Accept: 'text/event-stream' }, signal });
  if (!reponse.ok) {
    throw new Error(`GET /stream → HTTP ${reponse.status}`);
  }

  const trames = [];
  const decodeur = new TextDecoder();
  let tampon = '';

  try {
    for await (const morceau of reponse.body) {
      const arrivee = performance.now();
      tampon += decodeur.decode(morceau, { stream: true });

      // SSE separates events with a blank line. Everything before the last one
      // is complete; the remainder stays in the buffer.
      const blocs = tampon.split('\n\n');
      tampon = blocs.pop();

      for (const bloc of blocs) {
        // Heartbeat comments carry no data and are not fan-out.
        if (!bloc.includes('data:')) continue;
        const nom = bloc.match(/^event:\s*(.+)$/m)?.[1]?.trim() ?? 'message';
        const charge = bloc.match(/^data:\s*(.*)$/m)?.[1] ?? '';
        let courseId = null;
        try {
          const donnees = JSON.parse(charge);
          courseId = donnees.courseId ?? donnees.course?.id ?? donnees.donnees?.courseId ?? null;
        } catch {
          // A frame this script cannot parse still counts as fan-out: its
          // arrival time is the measurement, the payload only identifies the
          // course.
        }
        trames.push({ arrivee, nom, courseId });
      }
    }
  } catch (e) {
    if (e.name !== 'AbortError') throw e;
  }
  return trames;
}

/**
 * Groups frames into per-tick bursts and reports how long each burst took to
 * reach this client.
 *
 * The fan-out delay is the spread of one burst: the gap between the first delta
 * of a tick and the last. Absolute latency from the simulator's clock is not
 * measurable from here -- the frames carry simulated timestamps, and at
 * acceleration 10 those run ten times faster than the wall clock this script
 * reads.
 */
function analyserRafales(trames) {
  const rafales = [];
  let courante = null;

  for (const trame of trames) {
    if (courante === null || trame.arrivee - courante.derniere > SEUIL_RAFALE_MS) {
      courante = { premiere: trame.arrivee, derniere: trame.arrivee, trames: 0, courses: new Set() };
      rafales.push(courante);
    }
    courante.derniere = trame.arrivee;
    courante.trames += 1;
    if (trame.courseId !== null) courante.courses.add(trame.courseId);
  }

  // The first and last bursts are partial -- the window opened and closed
  // mid-tick -- so they would understate the spread. Dropped when there are
  // enough complete ones to be worth reporting.
  const completes = rafales.length >= 4 ? rafales.slice(1, -1) : rafales;
  return {
    rafales: completes,
    etalements: completes.map((r) => r.derniere - r.premiere),
    tailles: completes.map((r) => r.trames),
    concurrence: completes.map((r) => r.courses.size),
  };
}

// -------------------------------------------------------- dashboard timing

async function chronometrer(chemin, entetes) {
  const debut = performance.now();
  const reponse = await fetch(`${BASE}${chemin}`, { headers: entetes });
  await reponse.arrayBuffer();
  return { duree: performance.now() - debut, statut: reponse.status };
}

async function mesurerTableauBord(jeton, repetitions) {
  const entetes = { Authorization: `Bearer ${jeton}` };
  const aujourdhui = new Date().toISOString().slice(0, 10);
  const ilYAUneSemaine = new Date(Date.now() - 7 * 86400_000).toISOString().slice(0, 10);

  const requetes = {
    'kpi': `/api/v1/tableau-bord/kpi?date=${aujourdhui}`,
    'retards-par-ligne': `/api/v1/tableau-bord/retards-par-ligne?date=${aujourdhui}`,
    'heatmap': `/api/v1/tableau-bord/heatmap?du=${ilYAUneSemaine}&au=${aujourdhui}`,
    'distribution-retards': `/api/v1/tableau-bord/distribution-retards?du=${ilYAUneSemaine}&au=${aujourdhui}`,
  };

  const resultats = {};
  for (const [nom, chemin] of Object.entries(requetes)) {
    const durees = [];
    let statut = 0;
    for (let i = 0; i < repetitions; i += 1) {
      const mesure = await chronometrer(chemin, entetes);
      durees.push(mesure.duree);
      statut = mesure.statut;
    }
    resultats[nom] = { statut, p50: centile(durees, 0.5), p95: centile(durees, 0.95) };
  }
  return resultats;
}

// ------------------------------------------------------------------ memory

async function memoire() {
  try {
    const utilisee = await json('/actuator/metrics/jvm.memory.used');
    const octets = utilisee.measurements.find((m) => m.statistic === 'VALUE')?.value ?? 0;
    return `${(octets / 1024 / 1024).toFixed(0)} Mio`;
  } catch {
    return 'indisponible (démarrer l\'API avec --spring.profiles.active=metrologie)';
  }
}

// -------------------------------------------------------------------- main

async function principal() {
  console.log(`Mesures sur ${BASE}, fenêtre de ${DUREE_S} s.\n`);

  const lignes = await lignesActives();
  console.log(`Lignes écoutées (dérivées à l'exécution) : ${lignes.join(', ')}`);

  const jeton = await jetonResponsable();

  const controle = new AbortController();
  const fluxTermine = ecouterFlux(lignes, controle.signal);

  // Halfway through the window, not at the start. The dashboard cost worth
  // having is its cost at the peak, and a measurement taken while the fleet is
  // still departing reports a half-loaded server -- which is how a load test
  // ends up filing a number nobody can act on.
  await patienter(DUREE_S / 2);
  const tableauBord = await mesurerTableauBord(jeton, 5);
  const memoireSousCharge = await memoire();
  await patienter(DUREE_S / 2);

  controle.abort();
  const trames = await fluxTermine;

  const { rafales, etalements, tailles, concurrence } = analyserRafales(trames);

  console.log('\n== Diffusion SSE ==');
  console.log(`connexion                 1 (multiplexée sur ${lignes.length} canaux)`);
  console.log(`trames reçues             ${trames.length}`);
  console.log(`rafales complètes         ${rafales.length}`);
  console.log(`trames par rafale         p50 ${centile(tailles, 0.5) ?? '—'}  max ${tailles.length ? Math.max(...tailles) : '—'}`);
  console.log(`courses distinctes/rafale p50 ${centile(concurrence, 0.5) ?? '—'}  max ${concurrence.length ? Math.max(...concurrence) : '—'}`);
  console.log(`étalement de la rafale    p50 ${ms(centile(etalements, 0.5))}  p95 ${ms(centile(etalements, 0.95))}  max ${ms(etalements.length ? Math.max(...etalements) : null)}`);

  console.log('\n== Tableau de bord (5 appels chacun, sous charge) ==');
  for (const [nom, r] of Object.entries(tableauBord)) {
    console.log(`${nom.padEnd(22)} HTTP ${r.statut}  p50 ${ms(r.p50)}  p95 ${ms(r.p95)}`);
  }

  console.log('\n== JVM ==');
  console.log(`mémoire utilisée          ${memoireSousCharge}`);

  console.log('\n== Latence d\'ingestion ==');
  console.log("Mesurée côté producteur, pas ici : le simulateur journalise");
  console.log("« Latence d'ingestion : n=… p50=…ms p95=…ms p99=…ms max=…ms » chaque minute.");
  console.log('Relevez la dernière ligne de son journal à la fin de la fenêtre.');
}

principal().catch((e) => {
  console.error(`Échec : ${e.message}`);
  process.exit(1);
});
