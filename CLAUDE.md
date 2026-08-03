# Trino — SNCFT real-time train tracking

Internship project. Local demo only. Timeline: under 1 month.
Domain vocabulary is FRENCH and stays French in code (Gare, Ligne, Course,
Desserte, Passage, Retard, Incident). Code comments and docs: English.
All user-facing UI strings: French.

## Stack — pinned, do not change or search for versions

| Part | Version |
|---|---|
| Java | 21 |
| Spring Boot | 3.4.1 |
| PostgreSQL | 16 |
| Flyway | bundled with Boot |
| Next.js | 15 (App Router) |
| React | 19 |
| MapLibre GL JS | 4.7.1 |
| Tailwind | 4 |
| Node | 22 |

No Redis. No PostGIS. No Kafka. No message broker. If you think you need one,
you have misread the phase spec — re-read it.

## Repo layout

```
backend/          Maven multi-module
  api/            Spring Boot app (the only web server)
  simulateur/     Spring Boot app, separate process, GPS feed producer
frontend/         Next.js app
docker-compose.yml
docs/             specs — see pointer table below
```

## Commands

```bash
docker compose up -d db          # Postgres on 5432
cd backend && ./mvnw -q -pl api spring-boot:run          # API on 8080
cd backend && ./mvnw -q -pl simulateur spring-boot:run   # simulator
cd frontend && npm run dev                                # web on 3000
cd backend && ./mvnw -q test                              # backend tests
```

## Invariants — violating any of these is a bug

1. `Train` is rolling stock. It has NO status and NO delay. A `Course` is one
   dated run of a train on a line; status and delay live there. Never add a
   status column to `train`.
2. Theoretical times come from `desserte` (the stop pattern of a line).
   `retard = heure_reelle - heure_theorique`. Never hardcode a theoretical time.
3. The simulator NEVER writes to the database. It reads the day's courses over
   HTTP and POSTs positions to `/api/v1/ingest/positions`. It is a stand-in for
   real GPS hardware and must stay swappable.
4. Flyway migrations already applied are immutable. New change = new `V{n}__` file.
5. SSE payloads are deltas, never full snapshots. Channels are scoped per ligne
   or per gare, never global.
6. All money-free, all times stored as `timestamptz` in UTC, rendered in
   `Africa/Tunis`.
7. Controllers hold no logic. Controller -> Service -> Repository. DTOs are Java
   records in `dto/`, entities never cross the controller boundary.

## Where to look

| Need | File |
|---|---|
| Current progress, what's done | `docs/STATE.md` |
| Entities, fields, enums, ERD | `docs/architecture/domain-model.md` |
| Endpoints, DTO shapes, errors | `docs/architecture/api-contract.md` |
| Why a choice was made | `docs/architecture/decisions.md` |
| What to build now | `docs/phases/phase-{n}.md` |

Read ONLY the phase file for the phase you are on, plus whichever architecture
file it names. Do not read all phases. Do not explore the repo to "understand
the codebase" — the phase file lists the exact paths you need.

## Working rules

- One phase per session. Start with `/phase-start {n}`, end with `/phase-verify {n}`.
- Before writing code, state the file list you will create or edit, and wait.
- Never generate more than one Flyway migration per phase.
- When a phase is done, update `docs/STATE.md` and stop. Do not start the next phase.
