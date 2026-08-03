# State

Rewritten at the end of every phase. This is the handoff between sessions — the
next session reads this and `CLAUDE.md`, nothing else, before opening its phase
file. Keep it under 400 words. Debugging narrative goes to
`docs/RAPPORT-NOTES.md`, not here.

## Current phase

Phase 1 done and runtime-verified. Phase 2 next — but blocked on the
seed-data fix below.

## Done

- **Phase 0** — Maven parent + `api` + `simulateur` (placeholder), db-only
  compose. CRUD for `Gare`/`Ligne`/`Desserte`/`Train` (only GET wired to
  frontend), error envelope, clamped pagination, `ligne.trace` as parsed
  JSON. Next.js scaffold + gares list. Acceptance passed 2026-08-03.

- **Phase 1** — JWT auth (`iam` + `securite`). Four load-bearing roles:
  `VOYAGEUR`, `AGENT_CIRCULATION`, `RESPONSABLE_EXPLOITATION`,
  `ADMINISTRATEUR` (phases 5/6 gate on these names). Access 30 min / refresh
  7 days, HS256, no rotation, refresh tokens SHA-256-hashed, cookie `Path=/`.
  Login logged via `JournalService`; inactive accounts rejected. Référentiel
  writes gated both `@PreAuthorize` and at the URL level (`@Valid` runs
  before `@PreAuthorize` otherwise), reads/SSE/`/actuator` public. 401/403
  reuse `ErreurDTO`, UTF-8-safe everywhere. `UtilisateurController`
  read-only. Frontend: `lib/auth.ts`, connexion page, `middleware.ts`. Nine
  runtime-only bugs found across two passes (live testing, then a `reviewer`
  subagent) and fixed — none visible from the code alone; full list in
  `RAPPORT-NOTES.md` §3.

  Verified live twice (db/api on 5432/8081, not the unrelated services on
  8080): full acceptance script, a real browser login through to `/admin`
  and logged-out redirect, non-rotating refresh, deactivated-account
  rejection, SSE/actuator public access, logout requiring auth. API left
  running on 8081.

  **Demo logins** (password `Trino2026!`): `admin@sncft.tn`
  (ADMINISTRATEUR), `agent@sncft.tn` (AGENT_CIRCULATION),
  `responsable@sncft.tn` (RESPONSABLE_EXPLOITATION), `voyageur@sncft.tn`
  (VOYAGEUR).

## Migrations applied

`V1__referentiel.sql`, `V2__seed_reseau.sql` — 39 gares, 5 lignes, 46 dessertes,
26 trains. `V3__iam.sql` — `utilisateur`, `journal_connexion`, `refresh_token`
+ 4 seeded users. Applied and confirmed on the live dev DB.

## Blocking phase 2 — fix before `/phase-start 2`

Seed data inconsistent (`ordre`/`pk_km` must ascend together): L5's `SKN`
ordered after `MON` but positioned before it; `BEK`/`TBL` share a longitude
(zero-length segment); `TN103`/`MS501`-`MS505` exceed their line's
`vitesse_max_kmh`. Fix directly in `V2__seed_reseau.sql` + `docker compose
down -v` (don't edit `V1`, don't add a corrective migration). Verification
queries: `docs/phases/phase-2.md`.

## Deferred

- Query filters (`?region=`, `?q=`, `?type=`, `?ligneId=`): add when first
  needed.
- Ports 5432/8080 taken by unrelated services; API runs on 8081 locally,
  `application.yml` stays 8080. Phase 7 compose must publish `8081:8080`.
- `refresh_token` has no expiry sweep or per-user cap; grows one row per
  login/refresh. Fine for a demo, revisit if phase 7 needs cleanup.

## Open questions for the supervisor

- Spec puts `Statut` on `Train`; we split `Train`/`Course`. Confirm.
- Spec has no theoretical timetable entity; we added `Desserte`. Confirm.
- Which notification channel is reachable during the internship? Currently
  in-app only.

## Deviations from the cahier des charges

Maintained in `docs/RAPPORT-NOTES.md` section 4.
