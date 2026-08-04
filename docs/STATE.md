# State

Handoff between sessions. The next session reads this and `CLAUDE.md`, nothing
else, before opening its phase file. Keep it under 400 words. Debugging
narrative goes to `docs/RAPPORT-NOTES.md`, not here.

## Current phase

Phase 2 done, reviewed twice and runtime-verified. Phase 3 next, no blockers.

## Done

- **Phase 0** — référentiel CRUD, error envelope, clamped pagination, Next.js
  scaffold.
- **Phase 1** — JWT auth. Roles `VOYAGEUR`, `AGENT_CIRCULATION`,
  `RESPONSABLE_EXPLOITATION`, `ADMINISTRATEUR`. Access 30 min / refresh 7 days,
  no rotation. Writes gated by URL rule **and** `@PreAuthorize` (invariant 8).
  Demo logins `admin@` / `agent@` / `responsable@` / `voyageur@sncft.tn`,
  password `Trino2026!`.
- **Phase 2** — `horaire` holds 80 standing slots; `GenerateurCourses`
  materialises them at startup and daily at 03:00 into 80 courses / 683
  `passage_gare`, idempotent on (train, date, départ).
- RETOUR mirrors the stored desserte: `pk' = pkTotal - pk`, order reversed,
  arrival/departure offsets swapped so dwell is preserved.
- `GeometrieLigne` anchors each stop's `pk_km` to its gare's trace vertex;
  `projeter()` inverts a GPS fix back to a chainage. No PostGIS.
- `POST /ingest/positions` + `GET /ingest/courses-du-jour`, guarded by
  `FiltreCleIngestion` (`X-Ingest-Key`), a URL rule and `@PreAuthorize`.
- Simulator: separate headless process, HTTP client only, all state in memory,
  configurable `acceleration`.
- `ligne.trace` and gare coordinates are validated at the API boundary and
  invalidate the geometry cache on change.

## Verified

Acceptance: `courses-du-jour` first course fully populated (15 dessertes, 43
trace points); `position_course` 0 → 360 in 40 s; keyless POST → 401;
`./mvnw test` **18 green**. Trains stop at **0.000 km** from the terminus gare.
Full simulated day at x60: 73 finished, **23.3% ≥5 min late** (target ~25%),
earliest arrival 2.4 min early, observed/nominal speed ratio 0.9993.
`statut`/`retard_min`/`cause_retard` untouched by ingestion.

Two claims were proved rather than reasoned about. The `@Transactional` fix
was checked with a runtime probe plus a **negative control** — removing the
annotation makes `GenerateurCourses` log "hors transaction", restoring it
silences it. Cache eviction was checked by warming the cache, PUTting a ligne,
and confirming a Sousse ping still projects to exactly `pk 140.00`, `SOU→MSK`.

## Read this before phase 3

- **Chainage and ground speed are different units.** `avancement_km` and
  `pk_km` are chainage; a trace polyline runs up to 40% longer than
  `distance_km`. The `vitesseKmh` on a ping is a ground speed. Mixing them
  corrupts every ETA silently.
- `passage_gare` carries its own `pk_km` and `marge_min`. Do not re-join
  `desserte` on the hot path — RETOUR mirrors both.
- `eta_suivante` is deliberately null. `MachineEtatCourse`, `DetecteurSilence`
  and `EtatCirculationStore` do not exist yet. Ingestion records, never predicts.
- Restarting the simulator restarts departed runs from km 0, so they read as
  very late until they catch up.
- `GenerateurCourses.genererPour` asserts it is inside a transaction and logs
  an ERROR if not. If you refactor its callers, watch for that line.

## Standing deviations

| Deviation | Why |
|---|---|
| `V2` edited in place | Authorised by `phase-2.md`; unreleased seed on a rebuildable DB. **Not a precedent.** |
| `GeometrieLigne.projeter()` added | AVL reports coordinates, never chainage |
| Simulator speed from the timetable, not `vitesse_max_kmh` | At line max every train arrives ~80 min early |
| `GeometrieCourse` duplicates the anchoring maths | The HTTP contract is the only intended coupling (decision 2); parity test due in phase 7 |
| Seed geometry internally consistent, not surveyed | Real topology out of scope per `phase-2.md` |
| Seed checks in `CoherenceSeedTest`, not `GeometrieLigneTest` | They need a live DB; `GeometrieLigneTest` stays a pure unit test |
| `DesserteService` returns JPA entities to `circulation` | Decision 1 satisfied (service, not repository); a `DesserteVueDTO` would close it properly — **new, unresolved** |
| Idempotency is read-then-insert, not `ON CONFLICT DO NOTHING` | `genererPour` is `synchronized`, which covers the single instance this deploys as; a second process would still need it — **new** |

## Deferred

- **Phase 3**: `MachineEtatCourse`, `DetecteurSilence`, `eta_suivante`,
  `EtatCirculationStore`.
- **Phase 7**: compose publishes `8081:8080`; `position_course` growth
  unbounded; `refresh_token` expiry sweep; `GeometrieLigne`/`GeometrieCourse`
  parity assertion; `FiltreJwt`/`FiltreCleIngestion` are bare `Filter` beans so
  Boot also auto-registers them in the container chain (harmless today,
  `OncePerRequestFilter` masks it; `FilterRegistrationBean.setEnabled(false)`
  is the containment).
- **Unscheduled**: `/ingest/*` rate limit; référentiel query filters;
  `CoherenceSeedTest` skips when no DB unless `TRINO_DB_REQUIS=1`, which
  nothing sets.
- `phase-2.md`'s `DataSource` grep must exclude `target/`;
  `spring-boot-autoconfigure` lists `DataSourceAutoConfiguration` as a string
  in every Boot fat jar. Source and dependency trees are both clean.

## Correction to an earlier entry

A previous version of this file claimed `ApiExceptionHandler` was dropping
validation details for collection elements, "affecting every endpoint". **That
was wrong.** Spring already reports element violations as indexed
`FieldError`s (`trace[0]`, `pings[0].latitude`) — verified live. The original
empty `details` came from a malformed test payload hitting
`HttpMessageNotReadableException`, not from the handler. The handler now uses
`getFieldErrors()` with a fallback to global errors, which only matters if a
class-level constraint is ever added.

## Open questions for the supervisor

- Spec puts `Statut` on `Train`; we split `Train`/`Course`. Confirm.
- Spec has no theoretical timetable entity; we added `Desserte`. Confirm.
- Seed geometry is internally consistent, not surveyed. Confirm acceptable.
- Which notification channel is reachable during the internship?

## Running now

API on 8081; 80 courses for 2026-08-04 with positions from an accelerated run.
Simulator stopped. Start it with
`TRINO_API_BASE_URL=http://localhost:8081 ./mvnw -pl simulateur spring-boot:run`.
