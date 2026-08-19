# Phase 9 — Couverture, durcissement et démo (3 days)

The last phase. Everything above it is built and verified. This phase closes the
remaining gaps against the cahier des charges, sweeps up what earlier phases
deferred, and produces a demo that will not fail live — which costs more marks
than a missing module.

## Never hardcode an id or a name in a check

Three acceptance commands across this project could not pass as written, all for
the same reason: `DR201` (phase 4) was an example train that was never seeded,
and `cibleId: 1` (phase 8) was a backfilled course already at
`TERMINUS_ATTEINT`, which can never be late again. Both looked correct and both
measured nothing.

Every check and every line of `scripts/demo.sh` derives its subject from the
database at run time:

```bash
COURSE=$(docker exec trino-db psql -U trino -tAc \
  "select id from course where date_service = current_date
   and statut in ('EN_CIRCULATION','A_QUAI') order by depart_theorique limit 1")
```

A check that passes against a row which cannot exercise the behaviour is worse
than a failing one, because it is filed as evidence.

## Couverture du cahier des charges — combler ce qui se comble

An audit against the original specification found gaps. They are not equal in
cost, and three of them are cheap because the data already exists.

### Do these — the data is already there

1. **Three more exportable reports.** §4.11 lists six; two exist. `ServiceExport`
   was written generic over a report name in phase 5 precisely for this — each is
   one query method plus one map entry.
   - `retards-par-ligne` — the dashboard query already exists, it is simply not
     registered as a report.
   - `retards-par-gare` — the heatmap query aggregated by gare instead of by
     (gare, hour).
   - `disponibilite-trains` — nothing exists. Define it as the share of scheduled
     courses that actually ran (not `ANNULE`) per train and per ligne over a
     range. Computable entirely from `course`.

2. **Search criteria.** §4.9 asks for seven, `/recherche` binds three. Add
   `region` (join `gare`), `destination` (the course's last `passage_gare`), and
   an `heureDebut`/`heureFin` window on `depart_theorique`. All three are joins or
   predicates on data already present.

3. **A backup script.** §6 asks for automatic backup; only a mention in
   `decisions.md` exists. `scripts/sauvegarde.sh` running `pg_dump` with a dated
   filename and a retention count, plus the cron line in `README.md`. A concrete
   artifact beats a paragraph of intent.

### Do this one first — it closes the weakest claim in the project

**Load test at the specification's scale.** §6 says *plusieurs centaines de trains
simultanément*; the seed materialises 80 courses per day. The architecture should
hold — 300 trains at a 5 s tick is 60 messages/second, and the fan-out is the real
cost, not the ingest — but that is an argument, not a measurement, and it is the
only stated non-functional requirement that can be turned into a number in an
afternoon.

Generate a load profile of 300+ concurrent courses for one service day, run the
simulator against it, and record: ingest latency at p50/p95, SSE fan-out delay,
dashboard query times, and memory. Report the numbers whatever they are — if
something degrades, that finding is worth more than an untested claim.

### Do if time remains

4. **`DEPART` and `ARRIVEE` notification events.** §4.8 lists seven events; four
   exist. The data is on `passage_gare` and the emission points are inside
   `MoteurRetard`, where real times are stamped. Reuse the existing dedup key.
   Scope them to the subscriber's own target, not every stop of the course.

5. **PDF export.** §4.11 asks for it. Needs a new dependency (`openhtmltopdf` or
   equivalent) and a template. Lower value than the three reports above, because
   CSV and XLSX already cover the analytical use.

### Do NOT do these — defend them instead

6. **`CHANGEMENT_QUAI`.** It cannot fire as built: `quai` is derived
   deterministically at generation and never mutates, so no platform change exists
   to notify. Emitting it requires making `quai` mutable and giving an agent an
   action to change it — a feature, not emission logic. State it as scoped out,
   with the reason.

7. **Types de trains, causes de retard and rôles as editable configuration.**
   These are Java enums, so an administrator cannot add one without a redeploy.
   That is the correct design and worth defending: they are closed domain
   vocabularies, not settings. A new train type implies new business rules, a new
   delay cause implies a new mapping from incident types, and a new role implies
   new authorisation rules — none of which a database row can supply. Making them
   data would trade a compile-time guarantee for a runtime failure.

8. **HTTPS locally.** §6 asks for it. Terminating TLS in a local demo means a
   self-signed certificate, and the cookie policy was deliberately tuned for plain
   HTTP on localhost (decision 12) — switching would silently break the
   notification bell. Document where TLS terminates in a production deployment
   (reverse proxy, `X-Forwarded-Proto`, `Secure` on the cookies) and state that the
   application layer is already agnostic to it.

### Verify rather than count

**Responsive coverage.** A file-count of Tailwind breakpoints is not the
measurement — leaf components legitimately have none. Open every route at 375 px
in a real browser and record which ones fail: `/`, `/trains/[id]`, `/gares/[id]`,
`/affichage/[gareId]`, `/exploitation/tableau-bord`, `/exploitation/incidents`,
`/admin/*`. The public routes were checked in phase 4; the consoles added in
phases 6 and 7 were not.

## Sweep-up — carried from phases 3 through 8

Each was correctly deferred; this is where they land. None is large.

- **`?du=` without `?au=` skips the `PlageDates` window guard** and scans
  unbounded — measured 200 on `?du=1900-01-01`. Require both or neither.
- **`EtatCirculationStore` and `Dedoublonneur` are never evicted** except on
  `TERMINUS_ATTEINT`, so runs ending `ANNULE` or `ARRET_EXCEPTIONNEL` hold their
  window until restart.
- **`position_course` and `notification` grow unbounded.** Document a retention
  window; a partitioned table is out of scope.
- **`refresh_token` has no expiry sweep.** A scheduled delete of expired and
  revoked rows.
- **`EN_ATTENTE` notifications are never swept.** A process killed mid-dispatch
  left 344 rows stranded — the `ECHEC` path covers an adapter that throws, not a
  JVM that dies. A startup sweep moving stale `EN_ATTENTE` rows to `ECHEC` with a
  stated cause, plus the same on a schedule.
- **`FiltreJwt` / `FiltreCleIngestion` are bare `Filter` beans**, so Boot also
  auto-registers them in the container chain. `FilterRegistrationBean
  .setEnabled(false)` on each.
- **`/ingest/*` and `/auth/login` rate limits** (120/min/key and 10/min/IP), both
  declared in `api-contract.md` since phase 0. `LimiteurDebit` exists since phase
  8, so each is one line in `ConfigurationWeb`.
- **`requeteAuthJson` has three copies** — `auth.ts` holds the shared one;
  `incidents.ts` and `tableauBord.ts` still carry their own.
- **`TableauDepartsGare` has no periodic REST resync**, unlike the kiosk. A
  dropped delta leaves it stale until navigation.
- **`.gitignore` misses `*.log`.**

Explicitly **not** doing: forced password change on first login. It would catch
the four seeded demo accounts and risks breaking the login path on demo day.
Record it as future work in the report instead.

## Three things to write down rather than build

**A LIGNE or GARE subscriber gets one notification per late course.** Measured:
124 across 62 courses in 85 s at x20. That is the specified key implemented
faithfully — the bound is per course, not per subscriber — and at a busy station
it is also an honest picture of the day. It is not a demo risk either, because
the portal only offers "Suivre ce train": LIGNE and GARE subscriptions exist at
the API and have no button. The answer at real scale is a digest, not a tighter
cap; record it as future work.

**The account-subscription path cannot be reached from the browser.**
`EventSource` cannot send an `Authorization` header, so the portal never
authenticates its stream and every subscription made through the UI is anonymous.
The `utilisateur_id` branch is built and tested but unexercised by a human. Say
so plainly — the fix is a token in a cookie the stream endpoint reads, which is
what the anonymous path already does, and it is a design note rather than a
defect.

**Disponibilité 24h/24 at 99,9 %.** Not measurable in a local delivery. What
exists: a stateless application layer, Actuator health and readiness probes,
graceful degradation when the feed stops, and now a backup script. Report it as
design intent with the gap stated — decision 8 has the wording.

## Tests

Do not chase coverage. Four things are worth testing and the rest is not:

1. Delay computation and forward propagation — the core claim of the project.
2. The state machine — every transition in the domain model doc, including the
   silence timeout and the terminal-state guard added in phase 6.
3. Polyline interpolation — an off-by-one here puts trains in the sea. Add a
   parity assertion between `GeometrieLigne` (api) and `GeometrieCourse`
   (simulateur): same trace, same stops, identical chainage. The duplication is
   deliberate — the HTTP contract is the only intended coupling — but nothing
   currently stops the two implementations from drifting, and when they do, trains
   render off-track with no error anywhere.
4. One integration test: POST a ping, assert the passage is stamped, the delay is
   right, and an SSE event fires.

Skip controller tests, skip repository tests. The suite already stands at 217;
this phase should add the parity assertion and whatever the coverage work needs,
not bulk.

**Always run with the database.** Since phase 6 the DB-backed tests fail rather
than skip when Postgres is unreachable, which is deliberate — a green suite that
skipped 13 tests is worse than a red one. `TRINO_DB_URL` must be set.

## Docker compose

Full stack in one command: db, mailpit, api, simulateur, web. Healthchecks on db
before api starts, api before simulateur. Publish `8081:8080` so the host port
matches this machine while the repo stays portable. `docker compose up` on a clean
machine must produce a working demo — test it by pruning volumes and running it,
because it will fail the first time.

## RAPPORT-NOTES.md — the coverage table

`docs/RAPPORT-NOTES.md` already carries the debugging journal for phases 0
through 8 and the architecture decisions. What it still needs is the final
deliverable of this phase: **a table listing every requirement of the cahier des
charges with its status** — delivered, partially delivered, or scoped out with a
reason.

Build it from the audit above. It feeds straight into the report, and a jury reads
an explicit coverage table as rigour rather than as confession, provided nothing
in it is a surprise.

Note what changed since the earlier draft of that file: email notifications now
genuinely work (Mailpit), so the "adapter interface only" framing is obsolete —
`IN_APP` and `EMAIL` are delivered, `SMS` is a marked stub, push is not built.

## Demo script

`scripts/demo.sh` resets the database, seeds, generates two weeks of past courses
with realistic delays for the charts, and starts the simulator at acceleration 30.
Every id it uses is derived at run time.

Rehearse this order, timed, at least twice:

1. Public map, trains moving — 2 min
2. Click a delayed train, show the stop list: heure prévue barrée, heure estimée,
   heure réelle — 2 min
3. Station board on a second screen — 1 min
4. Follow a train, watch the bell increment, open the Mailpit inbox and show the
   email — 2 min
5. Log in as agent, declare an incident, watch it reach the public map — 2 min
6. Log in as responsable, dashboard and punctuality chart, export XLSX — 3 min
7. Log in as admin, create a user, show the one-time password, deactivate them —
   2 min
8. Kill the simulator, show graceful degradation — 1 min

Step 4 is the one that turns "we designed a notification system" into "here is the
email". Step 8 is the one that will impress an operations engineer, because it
shows you thought about what happens when the feed dies. Neither is optional.

## Acceptance

```bash
docker compose down -v && docker compose up -d
sleep 120
curl -s localhost:8081/actuator/health | grep -q '"status":"UP"'
curl -s -o /dev/null -w '%{http_code}' localhost:3000
cd backend && TRINO_DB_URL=jdbc:postgresql://localhost:5433/trino ./mvnw -q test
bash scripts/demo.sh

# the three new reports exist and export
RESP=$(curl -s -X POST localhost:8081/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"responsable@sncft.tn","motDePasse":"Trino2026!"}' | node -pe 'JSON.parse(require("fs").readFileSync(0)).accessToken')
for R in retards-par-ligne retards-par-gare disponibilite-trains; do
  curl -s -o /tmp/$R.xlsx -w "$R %{http_code}\n" -H "Authorization: Bearer $RESP" \
    "localhost:8081/api/v1/rapports/$R/export?du=$(date -d '-7 days' +%F)&au=$(date +%F)&format=xlsx"
done

# the four new search criteria bind
curl -s "localhost:8081/api/v1/recherche?region=Sousse" | grep -q contenu
curl -s "localhost:8081/api/v1/recherche?destination=Gab" | grep -q contenu
curl -s "localhost:8081/api/v1/recherche?heureDebut=06:00&heureFin=09:00" | grep -q contenu

# the backup produces a restorable dump
bash scripts/sauvegarde.sh && ls -la sauvegardes/ | tail -2
```

Then, in a real browser at 375 px, walk every route listed under *Verify rather
than count* and record which ones fail.
