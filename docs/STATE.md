# State

Rewritten at the end of every phase. This is the handoff between sessions — the
next session reads this and `CLAUDE.md`, nothing else, before opening its phase
file. Keep it under 400 words. Debugging narrative goes to
`docs/RAPPORT-NOTES.md`, not here.

## Current phase

Phase 0 done and verified. Phase 1 next.

## Done

- **Phase 0** — `docker-compose.yml` (db only). Maven parent + `api` +
  `simulateur` (placeholder). Spring Boot 3.4.1 app with entities, repos,
  services and controllers for `Gare`, `Ligne`, `Desserte`, `Train`. Full CRUD
  written, only GET wired to the frontend. `ApiExceptionHandler` implements the
  error envelope with correct per-exception status mapping. Pagination clamped
  in the service layer via `commun/PageableUtils` (page >= 0, taille 1-200).
  `ligne.trace` stored as JSON string, parsed in `LigneService`. Next.js 15
  scaffold with typed API wrapper and a gares list page. Acceptance script
  passed 2026-08-03, re-run after review fixes.

## Migrations applied

`V1__referentiel.sql`, `V2__seed_reseau.sql` — 39 gares, 5 lignes, 46 dessertes,
26 trains.

## Blocking phase 2 — fix before `/phase-start 2`

Seed data is internally inconsistent. The delay engine assumes `ordre` and
`pk_km` ascend together; violations produce silently wrong punctuality figures.

- L5 Métro du Sahel: `SKN` (Skanès) is ordered after `MON` (Monastir) but sits
  before it — reversed stop order.
- `BEK` and `TBL` share longitude `11.0342` — zero-length segment, breaks the
  ETA divisor.
- Several trains have `vitesse_max_kmh` above their line's (`TN103` 130 on a
  120 line; `MS501`-`MS505` on a 90 line).

Fix by editing `V2__seed_reseau.sql` directly and running
`docker compose down -v` — V2 is unreleased seed data on a rebuildable local
database, so a corrective migration would only clutter the history. Do NOT edit
`V1`. Real SNCFT branch topology is explicitly out of scope: only internal
consistency matters. Verification queries are in `docs/phases/phase-2.md`.

## Deferred

- Query filters (`?region=`, `?q=`, `?type=`, `?ligneId=`) not implemented.
  Add whenever a phase first needs a filtered list.
- Host ports 5432 and 8080 are taken on the dev machine by unrelated services.
  API runs locally on 8081 via override; `application.yml` stays 8080. Phase 7
  compose must publish `8081:8080`.

## Open questions for the supervisor

- Spec puts `Statut` on `Train`; we split `Train` / `Course`. Confirm.
- Spec has no theoretical timetable entity; we added `Desserte`. Confirm.
- Which notification channel is actually reachable during the internship?
  Currently scoped to in-app only.

## Deviations from the cahier des charges

Maintained in `docs/RAPPORT-NOTES.md` section 4.
