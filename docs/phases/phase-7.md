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

## Tests

Do not chase coverage. Four things are worth testing and the rest is not:

1. Delay computation and forward propagation — the core claim of the project.
2. The state machine — every transition in the domain model doc, including the
   silence timeout.
3. Polyline interpolation — an off-by-one here puts trains in the sea.
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
