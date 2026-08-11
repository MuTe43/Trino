# State

Handoff between sessions. The next session reads this and `CLAUDE.md`, nothing
else, before opening its phase file. Keep it under 400 words. Debugging
narrative goes to `docs/RAPPORT-NOTES.md`, not here.

## Current phase

Phase 6 done and verified. Phase 7 next.

## Done

- **Phase 0** — référentiel CRUD, error envelope, clamped pagination, Next.js scaffold.
- **Phase 1** — JWT auth, four roles, writes gated by a URL rule **and**
  `@PreAuthorize` (invariant 9). Demo logins `admin@` / `agent@` /
  `responsable@` / `voyageur@sncft.tn`, password `Trino2026!`.
- **Phase 2** — `horaire` materialises 80 courses / 683 `passage_gare` daily.
- **Phase 3** — the delay engine, `EtatCirculationStore` → … → `HubSse`.
- **Phase 4** — the passenger portal, map, station board.
- **Phase 5** — dashboards, exports, SSE multiplexing, backfill.
- **Phase 6** — incidents, exploitation console, public header.
  - Multi-channel SSE frame confirmed on **live** traffic (item 1).
  - DB-backed tests **fail closed** instead of skipping (item 2).
  - Public header over the map; `/affichage/{gareId}` stays chrome-free (item 3).
  - `exploitation/{domaine,dto,repo,service,web,evenement}`: declare, edit,
    resolve. `OUVERT→EN_COURS→RESOLU`, `OUVERT→RESOLU`; anything else — a
    self-transition included — is `409 CONFLIT`.
  - **Resolution is its own endpoint** (`POST /incidents/{id}/resolution`,
    responsable only); `PATCH` refuses `RESOLU` for *every* role. Decision 9.
  - The incident form also carries the agent's other two powers, `actionCourse`
    and an explicit `causeRetard`, both through `MachineEtatCourse`.
  - `TypeIncident.causeAssociee()` **suggests** and never overwrites.
  - KPI incident tiles wired; `/rapports/incidents` + export entry.

## Migrations applied

`V1__referentiel` · `V2__seed_reseau` · `V3__iam` · `V4__circulation` ·
`V5__index_circulation` · `V6__backfill_quai_passage_gare` · `V7__incidents`.

One migration this phase, numbered **V7** — the phase file first said `V6`,
already taken (invariant 4); the user corrected it.

## Verified

`./mvnw test` **145 green, 0 skipped** (92 after phase 5). `npm run build`,
`tsc --noEmit`, `eslint` all green.

**Run it as `TRINO_DB_URL=jdbc:postgresql://localhost:5433/trino ./mvnw test`.**
The DB-backed tests now **fail** when Postgres is unreachable — measured: 2
errors, 0 skipped, where it used to be 13 silent skips. Opt out explicitly with
`-Dtrino.tests.sansDb=true` (or `TRINO_TESTS_SANS_DB=1`).

Every command of the Acceptance section was run against the live API, twice —
once before the verify fixes and once after:

| # | Check | Result |
|---|---|---|
| 1 | agent login → token | pass |
| 2 | declaration → id, `test -n "$ID"` | pass (see substitutions) |
| 3 | `select cause_retard from course where id = 1` | `SIGNALISATION` |
| 4 | agent `PATCH {"statut":"RESOLU"}` | `403` |
| 5 | `grep -qE 'event: ?incident'` on `/stream/lignes/1` | `exit=0` |

Substitutions, all documented: **8080 → 8081**, **`jq` → `node`**, **`psql` →
`docker exec trino-db psql`** (no local client; the container is on 5433), and
the request body sent from a **UTF-8 file**.

**That last one is new.** Passed inline, `curl.exe` receives the accent of
`bloqué` as a single CP1252 byte `e9` and Jackson rejects the body as invalid
UTF-8 (`400`, empty `details`); written to a file by bash it is `c3 a9` and the
request succeeds. Git Bash transcodes argv to the Windows ANSI codepage when it
launches a native binary. A shell builtin does not cross that boundary, so
`printf | xxd` shows correct UTF-8 and *disproves nothing* — the earlier handoff
had the mechanism wrong on that basis. Environment only: a browser sends UTF-8.

Also verified live: responsable `PATCH RESOLU` → 403 too; `POST /resolution`
agent 403 / voyageur 403 / anonyme 401 / responsable 200; forbidden role +
malformed body → **403, not 400**; gare-only incident reaching every ligne
serving that gare; KPI tiles non-zero; incidents CSV/XLSX export with BOM and
`;`, **0** ERROR lines in the API log; header present on `/` and `/gares/{id}`,
absent on `/affichage/{id}`; `/exploitation/*` → 307 `/connexion`.

## Fixed after review

Two review passes; the second covered the frontend, which the first had not.

- **A cancelled course could be resurrected from the console.**
  `appliquerActionAgent` had no terminal-state guard, so `ANNULE →
  ARRET_EXCEPTIONNEL` was accepted — and ARRET_EXCEPTIONNEL is deliberately
  non-terminal, so the next ping re-derived the run to `EN_CIRCULATION`. Guarding
  only `evaluer` guarded the feed and left the console as a way in. Now `409`.
- **The console's ligne filter and the SSE routing disagreed.** The filter
  matched `i.ligne.id` only, while a gare-only incident publishes on every ligne
  serving that gare — so the console refreshed on a delta and then hid the row,
  which reads as a lost declaration. `ligneId` now means "concerns this ligne" on
  both sides.
- **A validation error with nowhere to render.** `survenuAtPasseOuPresent` was
  added to the DTO after the form was written; the key was stored and never
  displayed, so a mistyped year gave "Le formulaire contient des erreurs." with
  every field unmarked. Aliased onto the `survenuAt` field.
- Earlier pass: `:param is null` → `could not determine data type of parameter
  $7` (a **500 on the console's default view**); `resoluAt` stamped from
  `HorlogeCirculation` (**−5.1 h** to resolution); `rs.wasNull()` read after two
  later `getLong`s (every unresolved bucket reported **0.0 h**, "resolved
  instantly"); self-transition answering 200 while resolution answered 409; a
  gravité edit reaching no subscriber; `ligneId`/`courseId` never cross-checked;
  the supervision map holding ~45 channels; no window guard on
  `/rapports/incidents`; `Pageable` built in the controller.
- **`StreamControllerTest.unSeulBattementParConnexion` was still flaky** and went
  red during this verify. Phase 5 narrowed the race by measuring a delta; there
  is no window left to shrink, because the scheduled `fixedRate` beat can land
  between the two reads. Now compared between a four-channel and a one-channel
  connection: a stray beat reaches both, so it cancels. Three consecutive runs
  green, plus the full suite.
- Nine files my scripted edits had rewritten LF → CRLF are back to LF;
  `ServiceExport.java` reads as 39 changed lines instead of 249.
  `CarteReseau.tsx` was CRLF before this phase and is left alone.

## Not verified

- The map still has not been *seen* rendering: the in-app browser reports
  `document.hidden` permanently true. Subscriptions, payloads, built CSS and
  route table are verified; drawing is not. Unchanged since phase 4.

## Deferred

- **Phase 7**: `EtatCirculationStore` eviction; `position_course` growth;
  `refresh_token` sweep; `FiltreJwt`/`FiltreCleIngestion` double-registered;
  `.gitignore` misses `*.log`. **New:** `GET /incidents/ouverts` is unpaginated
  by design — a supervision map must not hide an incident left open yesterday —
  so it grows if incidents are never resolved.
- **Unscheduled**: `/ingest/*` rate limit; référentiel query filters;
  `TableauDepartsGare` has no periodic REST resync.

## Standing deviations

| Deviation | Why |
|---|---|
| `docker-compose.override.yml` publishes the db on **5433** | A `PostgreSQL_For_Odoo` service owns 5432 here. Same class as 8080→8081. |
| `jq` and `psql` absent; `node` and `docker exec` used | Same URLs, same assertions, different clients. |
| **Request bodies with accents sent from a UTF-8 file** | Git Bash transcodes argv to the Windows ANSI codepage for a native binary, so an inline accent reaches `curl.exe` as CP1252. Environment only. |
| `IncidentService` reads repositories from four other modules | It needs course, gare, ligne and utilisateur to build one incident. Flagged by review; the alternative is a facade per module, judged not worth the indirection at this size. |
| An agent's two extra powers live on the incident form | The phase grants them but names no endpoint and adds no course controller. The declaration *is* the reason for the cancellation. |
| `impact` is `not null` | `domain-model.md` marks only `gare_id`, `ligne_id`, `course_id`, `resolu_at` nullable. Followed literally. |
| Two check constraints beyond the doc (`resolu_at` biconditional, ≥1 location) | Recorded in `domain-model.md`. |
| Earlier deviations from phases 2–5 | Unchanged. |

## Open questions for the supervisor

- Spec puts `Statut` on `Train`; we split `Train`/`Course`. Confirm.
- Spec has no theoretical timetable entity; we added `Desserte`. Confirm.
- Seed geometry is internally consistent, not surveyed. Confirm acceptable.
- Which notification channel is reachable during the internship?

## Running now

Postgres `trino-db` on **5433**, API on **8081**, `next start` on 3000,
simulator x20. History synthesised for **2026-07-26 → 2026-08-08**; 2026-08-09
to 2026-08-11 are simulated days. Course 1201 is `ANNULE` by an agent action, so
`trainsAnnules` = 1. Five demo incidents remain; rows created while testing were
deleted.

```
docker compose up -d db     # override publishes 5433
cd backend && ./mvnw -q -pl api spring-boot:run \
  -Dspring-boot.run.arguments="--server.port=8081 --spring.datasource.url=jdbc:postgresql://localhost:5433/trino"
TRINO_API_BASE_URL=http://localhost:8081 TRINO_SIM_ACCELERATION=20 \
  TRINO_SIM_HEURE_DEBUT=05:25 ./mvnw -q -pl simulateur spring-boot:run
TRINO_DB_URL=jdbc:postgresql://localhost:5433/trino bash scripts/backfill.sh
cd frontend && npm run build && npm run start
```
