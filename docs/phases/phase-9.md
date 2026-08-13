# Phase 7 — Durcissement + démo (1.5 days)

Reserve phase. Do this even if phase 6 was cut — a demo that fails live costs
more marks than a missing module.

## Goal

Tests where they matter, one-command startup, honest documentation of what was
and was not built, and a rehearsed demo.

## Build

```
backend/api/src/test/java/.../MoteurRetardTest.java       unit, the delay maths
backend/api/src/test/java/.../MachineEtatCourseTest.java  unit, every transition
backend/api/src/test/java/.../GeometrieLigneTest.java     unit, interpolation
backend/api/src/test/java/.../IngestionIT.java            Testcontainers, end to end
docker-compose.yml                                        add api, simulateur, web
README.md
docs/RAPPORT-NOTES.md                                     material for the report
scripts/demo.sh                                           reset + seed + accelerate
```

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

## Sweep-up — carried from phases 3 through 8

Each was correctly deferred; this is where they land. None is large.

- **`?du=` without `?au=` skips the `PlageDates` window guard** and scans
  unbounded — measured 200 on `?du=1900-01-01`. Require both or neither.
- **`EtatCirculationStore` is evicted only on `TERMINUS_ATTEINT`**, so runs
  ending `ANNULE` or `ARRET_EXCEPTIONNEL` hold their window until restart.
- **`position_course` grows unbounded** (~30 rows/tick). Document a retention
  window; a partitioned table is out of scope.
- **`refresh_token` has no expiry sweep.** A scheduled delete of expired and
  revoked rows.
- **`FiltreJwt` / `FiltreCleIngestion` are bare `Filter` beans**, so Boot also
  auto-registers them in the container chain. `FilterRegistrationBean
  .setEnabled(false)` on each.
- **`/ingest/*` rate limit** (120/min/key), specified in `api-contract.md` and
  never built.
- **`.gitignore` misses `*.log`.**
- **`EN_ATTENTE` notifications are never swept.** A process killed mid-dispatch
  left 344 rows stranded — the `ECHEC` path covers an adapter that throws, not a
  JVM that dies. A startup sweep moving stale `EN_ATTENTE` rows to `ECHEC` with
  a stated cause, plus the same on a schedule.
- **`Dedoublonneur` and `EtatCirculationStore` eviction**, and `notification`
  growth alongside `position_course`.
- **`/auth/login` rate limit** (10/min/IP), declared since phase 0. `LimiteurDebit`
  exists now, so this and `/ingest/*` are one line each in `ConfigurationWeb`.
- **`requeteAuthJson` has three copies** — `auth.ts` holds the shared one;
  `incidents.ts` and `tableauBord.ts` still carry their own.
- **`TableauDepartsGare` has no periodic REST resync**, unlike the kiosk. A
  dropped delta leaves it stale until navigation.

Explicitly **not** doing: forced password change on first login. It would catch
the four seeded demo accounts and risks breaking the login path on demo day.
Record it as future work in the report instead.

## Two things to write down rather than build

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

## Tests

Do not chase coverage. Four things are worth testing and the rest is not:

1. Delay computation and forward propagation — the core claim of the project.
2. The state machine — every transition in the domain model doc, including the
   silence timeout.
3. Polyline interpolation — an off-by-one here puts trains in the sea.
   Add a parity assertion between `GeometrieLigne` (api) and `GeometrieCourse`
   (simulateur): same trace, same stops, identical chainage. The duplication is
   deliberate — the HTTP contract is the only intended coupling — but nothing
   currently stops the two implementations from drifting, and when they do,
   trains render off-track with no error anywhere.
4. One integration test: POST a ping, assert the passage is stamped, the delay
   is right, and an SSE event fires.

Skip controller tests, skip repository tests, skip the frontend entirely.

## Docker compose

Full stack in one command. Healthchecks on db before api starts, api before
simulateur. `docker compose up` on a clean machine must produce a working demo.
Test this by pruning volumes and running it — it will fail the first time.

## RAPPORT-NOTES.md

Write this while it is fresh. It is not the report, it is the raw material:

- The `Train` vs `Course` correction and why the spec's version breaks
- The missing `Desserte` entity
- Why the simulator sits outside the system boundary (decision 2) — this is the
  centrepiece
- Why no Redis, no PostGIS, no microservices (decisions 1, 4, 5)
- What "99.9%" actually means here and what was implemented instead (decision 8)
- What was not built and why: SMS/email/push channels, PDF export, and anything
  cut from phase 6. Frame each as a scoped decision with the design in place,
  not as an omission.

A jury will respect a documented cut far more than a silently missing feature.

## Demo script

`scripts/demo.sh` resets the DB, seeds, generates two weeks of past courses with
realistic delays for the charts, and starts the simulator at acceleration 30.

Rehearse this order, timed, at least twice:
1. Public map, trains moving — 2 min
2. Click a delayed train, show the stop list with theoretical vs real — 2 min
3. Station board on a second screen — 1 min
4. Log in as agent, declare an incident, watch it appear on the public map — 2 min
5. Log in as responsable, dashboard and punctuality chart, export XLSX — 3 min
6. Kill the simulator, show graceful degradation — 1 min

Step 6 is optional but it is the one that will impress an operations engineer,
because it shows you thought about what happens when the feed dies.

## Acceptance

```bash
docker compose down -v && docker compose up -d
sleep 90
curl -s localhost:8080/actuator/health | grep -q '"status":"UP"'
curl -s -o /dev/null -w '%{http_code}' localhost:3000
cd backend && ./mvnw -q test
bash scripts/demo.sh
```
