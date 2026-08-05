# State

Handoff between sessions. The next session reads this and `CLAUDE.md`, nothing
else, before opening its phase file. Keep it under 400 words. Debugging
narrative goes to `docs/RAPPORT-NOTES.md`, not here.

## Current phase

Phase 4 done, reviewed. Phase 5 next. One acceptance item unverified — see
**Carry to phase 5**.

## Done

- **Phase 0** — référentiel CRUD, error envelope, clamped pagination, Next.js scaffold.
- **Phase 1** — JWT auth, four roles, writes gated by a URL rule **and**
  `@PreAuthorize` (invariant 8). Demo logins `admin@` / `agent@` /
  `responsable@` / `voyageur@sncft.tn`, password `Trino2026!`.
- **Phase 2** — `horaire` materialises 80 courses / 683 `passage_gare` daily;
  `GeometrieLigne` anchors each stop's `pk_km`; `/ingest/*` behind `X-Ingest-Key`.
- **Phase 3** — the delay engine: `EtatCirculationStore` → `MoteurRetard` →
  `MachineEtatCourse` → `CalculateurEta` → `DiffuseurCirculation` → `HubSse`.
- **Phase 4** — the passenger portal.
  - `DepartGareDTO` for `/gares/{id}/departs`; `destination` is the terminus
    gare, resolved server-side in one batched query. No N+1 (two queries total).
  - `GenerateurCourses` assigns `quai` deterministically from the gare's
    `nb_quais`; `V6` backfills rows that predate it.
  - CORS origins from `trino.cors.origines`; a `*` value now fails at startup
    with a readable message instead of 500-ing every preflight.
  - `GET /courses?statut=` binds `List<StatutCourse>`, filtered in SQL. The map
    snapshot is one request.
  - SSE client disconnects log at DEBUG. Two leaks, both closed: `HubSse.envoyer`
    catches bare `IOException` (the Windows form), and
    `ConfigurationPlanificateur` gives the scheduler an `ErrorHandler` — the
    heartbeat's failures never reached the emitter path at all.
  - Frontend: `lib/{sse,types,api,couleurs,temps,departs}.ts`, `CarteReseau`
    (MapLibre + OSM), `MarqueurTrain` (shared rAF interpolation), `BarreRecherche`,
    `ListeArrets`, and routes `/`, `/trains/[id]`, `/gares/[id]`, `/affichage/[gareId]`.

## Migrations applied

`V1__referentiel` · `V2__seed_reseau` · `V3__iam` · `V4__circulation` ·
`V5__index_circulation` · **`V6__backfill_quai_passage_gare`** (new, the
phase's only migration: backfills `passage_gare.quai` for rows generated before
`GenerateurCourses` assigned it). V1–V5 untouched.

## Verified

`./mvnw test` **65 green** (was 49 after phase 3; 52 after the DTO, 65 now).
`npm run build` green. Against a live API on 8081 + simulator at x20:

| Check | Result |
|---|---|
| `grep` for client-side time arithmetic | pass — no output |
| Search a real seed train number (`TN101`) and a gare name | pass |
| `/affichage/1` — 9 rows, no scroll, real quais, live clock | pass |
| Board delayed row: struck theoretical + revised in status colour | pass |
| Board `ANNULE` row: all `--ardoise`, destination struck, `Supprimé` red | pass |
| Board and map at 375px | pass — no overflow, train/voie dropped |
| Board 1080p geometry: 108px rows, 9 fit | pass |
| Map markers colour-coded, moving by sub-pixel rAF interpolation | pass |
| One `/courses?statut=EN_CIRCULATION,RETARDE` request, not two | pass |
| CORS `*` rejected at startup | pass |
| SSE disconnect: 10 abandoned clients, 3 heartbeats → 0 ERROR lines | pass |

## Fixed after review

- **`couleurs.ts` built Tailwind class names by interpolation.** Tailwind 4 only
  generates utilities whose names appear as *literal* strings, so 9 of 10 status
  classes were never emitted and the whole status ramp rendered as inherited
  text. Invisible to tsc, ESLint and the build. Now a literal lookup table.
  **If you add a token, spell the class out in full.**
- A `retard` delta overwrote each revised stop's `classeRetard` with the
  course-level one, so a recovered downstream stop showed its own `retardMin`
  in the colour of the course's worst delay. Now `classeDeRetard(revision.retardMin)`.
- The map subscribed to every ligne in the référentiel, not the visible ones.
- Search grouped trains under a header reading "Lignes", and two runs of the
  same train were indistinguishable; rows now carry their departure time.
- `.env.local.example` still named `NEXT_PUBLIC_API_URL`, which nothing reads.

## Carry to phase 5

- **Unverified: "exactly one EventSource per open ligne channel, closes on
  navigation."** Must be measured in a real Chrome window against
  `npm run start` — the embedded browser reports `document.hidden` as
  permanently true and stops compositing, so MapLibre never renders and the
  count is meaningless there. Same reason the 3-minute movement run and the
  kill/restart-simulator transitions were not re-run end to end.
- `ANNULE` styling is verified, but only via a manual DB edit; nothing creates
  a cancelled course until phase 6.

## Deferred

- **Phase 7**: SSE needs ~1 socket per ligne and a browser allows ~6 per origin
  over HTTP/1.1, shared with REST — viewport filtering bounds this only when
  zoomed in, not at full-network zoom. Needs h2 (so TLS) or a rethink that keeps
  invariant 5. Also: `EtatCirculationStore` evicted only on `TERMINUS_ATTEINT`;
  compose publishes `8081:8080`; `position_course` growth unbounded;
  `refresh_token` sweep; `FiltreJwt`/`FiltreCleIngestion` double-registered;
  `.gitignore` does not cover `*.log`.
- **Phase 6**: `/connexion` is now the one screen contradicting phase 4's design
  direction (centred card, `bg-blue-600`, `font-semibold` at a weight the app
  does not load).
- **Unscheduled**: `/ingest/*` rate limit; référentiel query filters;
  `TableauDepartsGare` has no periodic REST resync (only the kiosk does);
  `EtatFluxSse`/`useEtatFluxSse` exported but unused.

## Standing deviations

| Deviation | Why |
|---|---|
| `HorlogeCirculation` added, not in the phase file | The feed carries its own clock; at x20 a ping is stamped hours from wall-clock now. |
| Restarting the simulator with an earlier `heure-debut` moves that clock backwards | Simulation artifact; the self-heal clears stranded estimates. |
| `CourseService`, `DepartsService`, `DiffuseurCirculation` added | Invariant 7: controllers hold no logic. |
| `MoteurRetard` stamps every passed unstamped stop, not only the last | A feed gap would otherwise strand a stop null all day. |
| `CourseRepository` no longer needs its `:statut = c.statut` inversion | The CSV filter is `c.statut in :statuts`, which never matches the `\.statut =` grep. |
| Frontend additions beyond the Build list: `globals.css`, `layout.tsx`, `api.ts`, `couleurs.ts`, `temps.ts`, `departs.ts`, `affichage/layout.tsx`, `maplibre-gl` | Tokens/fonts must live somewhere; Next 15 forces a Server/Client split for any route that server-fetches and then needs SSE. |
| `quai` is `train.id + gare.id` mod `nb_quais` | Deterministic and stable across regeneration. Platform conflicts between different trains are not modelled. |
| Seed geometry internally consistent, not surveyed | Real topology out of scope per `phase-2.md`. |

## Open questions for the supervisor

- Spec puts `Statut` on `Train`; we split `Train`/`Course`. Confirm.
- Spec has no theoretical timetable entity; we added `Desserte`. Confirm.
- Seed geometry is internally consistent, not surveyed. Confirm acceptable.
- Which notification channel is reachable during the internship?

## Running now

Postgres `trino-db`, API on **8081**, production frontend (`next start`) on
3000, simulator at x20. **2026-08-05's circulation was reset during phase-4
verification** so the day could be replayed — generated data only, and one
course (id 118, `TN105`) was set to `ANNULE` by hand for the board's cancelled-row
test. Restart with:

```
cd backend && ./mvnw -q -pl api spring-boot:run -Dspring-boot.run.arguments=--server.port=8081
TRINO_API_BASE_URL=http://localhost:8081 TRINO_SIM_ACCELERATION=20 \
  TRINO_SIM_HEURE_DEBUT=05:25 ./mvnw -q -pl simulateur spring-boot:run
cd frontend && npm run build && npm run start
```
