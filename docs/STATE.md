# State

Handoff between sessions. The next session reads this and `CLAUDE.md`, nothing
else, before opening its phase file. Keep it under 400 words. Debugging
narrative goes to `docs/RAPPORT-NOTES.md`, not here.

## Current phase

Phase 8 done, reviewed and verified. Phase 9 next.

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
- **Phase 7** — the administrator console, `/admin/{,gares,lignes,trains,utilisateurs,journal}`.
- **Phase 8** — notifications et alertes; two use cases that had no implementation.
  - **Following a train needs no account.** The server mints a `SecureRandom`
    token on the first `POST /abonnements` and returns it as an `HttpOnly`
    cookie — never in a body, a URL or a log. Decision 12.
  - **The client never names its `abonne:` channel.** `StreamController` derives
    it from the cookie/header and ignores any `abonnes=`; frames carry the alias
    `abonne:moi`, since the real name embeds the token.
  - `MoteurNotification` listens to the **same** `Evenement*` records the two
    diffuseurs already publish. No second event stream; circulation still has no
    compile-time knowledge that notifications exist.
  - Every foreign read goes through a **service**. No foreign repository is
    injected anywhere under `notification/` (decision 1).
  - **Mailpit** in docker-compose: real SMTP, real inbox at `localhost:8025`.
  - `/admin/alertes`, on the phase-7 `TableauEditable` + `DialogueEdition` pair
    (which gained a `multi` field type).
  - `LimiteurDebit` — the project's first working rate limit, on the one
    unauthenticated endpoint that sends mail.

## Migrations applied

`V1__referentiel` · `V2__seed_reseau` · `V3__iam` · `V4__circulation` ·
`V5__index_circulation` · `V6__backfill_quai_passage_gare` · `V7__incidents` ·
`V8__notifications`.

V8 carries the two `journal_connexion` indexes phase 7 was forbidden a migration
for, and seeds **four `regle_alerte` rows** — the acceptance expects a
notification before it ever creates a rule.

## Fixed after review

The review found eight items; four were fixed in code, three corrected in the
docs, one recorded below.

- **Editing an open incident notified everybody a second time.**
  `IncidentService.mettreAJour` republishes the same payload on any change, and a
  description-only edit leaves `statut` at `OUVERT` — which the engine read as a
  second declaration. Measured live: a description PATCH took **4 notifications
  to 8** and sent a second identical email. The cause was the deduplication key:
  incidents were keyed on the *course*, and a ligne-wide incident carries a null
  course, so every such incident shared one window while an edit of one still got
  through. Now keyed on the **incident**. Measured after: **4 → 4**. Two
  regression tests pin it.
- **`graviteMin` could not be cleared, and the console offered a control saying
  it could.** An absent field means unchanged, so a null and an omission are the
  same JSON; choosing "Toutes" returned 200 with the old severity intact. An
  explicit `effacerGraviteMin` flag now carries the intent. Measured: `MOYENNE` →
  `MOYENNE` without it, `MOYENNE` → `null` with it.
- **The subscriber token could reach a log line.** Every log in `HubSse` names
  the channel it was working on, and an `abonne:` name embeds the credential —
  inert at the configured level, but the opposite of what the phase file,
  decision 12 and `JetonAbonne`'s own javadoc promise. Masked at the log
  boundary.
- `ResultatAbonnement` moved to `dto/` (invariant 7 — it crosses the controller
  boundary); the rules list now sorts by enum ordinal rather than by the stored
  varchar, which was alphabetical and disagreed with the console's own labels.

## Verified

`./mvnw test` **217 green, 0 failures, 0 errors, 0 skipped** (171 after phase 7).
`npm run build`, `tsc --noEmit`, `eslint --max-warnings=0` green.

**Run it as `TRINO_DB_URL=jdbc:postgresql://localhost:5433/trino ./mvnw test`.**

Every Acceptance command run live against 8081 with the standing substitutions
(8080→8081, `jq`→`node`, `psql`→`docker exec`). The phase file is unedited.

| Check | Expected | Got |
|---|---|---|
| `POST /abonnements` | created | 201, `Set-Cookie: jeton_abonne` HttpOnly |
| same again | not an error | 200, row updated |
| `GET /notifications` \| `.total` | ≥ 1 | **56** |
| Mailpit `.total` | ≥ 1 | **9** |
| `POST /regles-alerte` (admin) | 201 | 201 |
| `GET /regles-alerte` (voyageur) | 403 | 403 |
| *(added)* voyageur + malformed body | 403 not 400 | 403 |
| ingestion with SMTP dead | < 200 ms | **18–29 ms**, 202 |
| the row it produced | `ECHEC` + `erreur` | `MailSendException: Mail server connection failed…` |

**Two acceptance commands cannot pass as written, and neither is a code defect:**

- `cibleId: 1` — course 1 is a **backfilled 2026-08-04 run, already
  `TERMINUS_ATTEINT`**. It can never be late again, so that subscription can
  never produce a notification. Re-run against a course actually circulating
  today: 56 notifications, 9 emails. Today's course ids start at 1361.
- The dedup query groups by `(abonnement_id, evenement)` and fails over 3, but
  one emission writes one row **per channel** and a LIGNE/GARE subscription spans
  every late course on its target. Grouped on the real key —
  `(abonnement, evenement, course, canal)` — the **maximum is 2** across 5 383
  ingested pings, which is the guard working. Supervisor's call whether to
  loosen the query or tighten the rule.

## Deferred

- **Into phase 9** — a retry or startup sweep for `EN_ATTENTE` notifications
  (see the deviation table); forced password change on first login; claiming an
  anonymous subscription at login (out of scope by decision 12); the
  `/auth/login` and `/ingest/*` rate limits, still declared in `api-contract.md`
  and unimplemented — `LimiteurDebit` now exists and each is one line in
  `ConfigurationWeb`.
- **Raised for the supervisor** — the dedup key bounds messages per *course*, not
  per *subscriber*. A GARE subscriber received 124 notifications across 62
  courses in 85 s at x20. That is the spec's key implemented faithfully; whether
  a per-subscriber cap was intended is a spec question, not a bug.
- **Still open** — `Dedoublonneur` and `EtatCirculationStore` eviction;
  `position_course` and `notification` growth; `refresh_token` sweep;
  `FiltreJwt`/`FiltreCleIngestion` double-registered; `.gitignore` misses `*.log`;
  `GET /incidents/ouverts` unpaginated by design.
- **Known and accepted** — an incident edited more than 30 simulated minutes
  after declaration re-notifies; `?du=` without `?au=` scans unbounded; a ligne
  whose trace has fewer than two points cannot be renamed; `requeteAuthJson` is
  duplicated in `incidents.ts` and `tableauBord.ts`.

## Standing deviations

| Deviation | Why |
|---|---|
| db on **5433**, API on **8081** | Unrelated services own 5432 and 8080 here. |
| `jq`/`psql` absent; `node` and `docker exec` used | Same URLs, same assertions. |
| **A notification killed in flight stays `EN_ATTENTE` for ever** | Nothing retries and nothing sweeps at startup. Measured: 344 rows left by a `taskkill /F` of the API, none at all across a clean run. The `ECHEC` path covers an adapter that throws, not a process that dies. |
| **A second replay needs today's courses reset first** | After one run the day's 80 courses are `TERMINUS_ATTEINT` and the simulator has nothing to move. `docs/RUNBOOK.md` §9 has the reset. |
| **Mailpit has no volume** | The inbox is meant to be thrown away; restarting the container empties it. |
| The bell **renders nothing** until you follow something | A bell that cannot ring is chrome. |
| Only `IN_APP` and `EMAIL` offered in the UI | `SMS` is a stub with no phone number in the model; `AFFICHAGE` is the station board, already delivered. |
| Notification read state is **client-side** | No `lu` column; a write per glance on a public endpoint is worse. Ids are monotonic. |
| The **account** subscription path is unreachable from the UI | `EventSource` cannot send `Authorization`, and the portal sends none. Built and tested, not exercised by the browser. |
| Earlier deviations from phases 2–7 | Unchanged. |

## Running now

Postgres `trino-db` on **5433** and `trino-mailpit` on **1025/8025** are up; the
API, simulator and frontend are stopped. `docs/RUNBOOK.md` has every command to
bring them back, query the database and re-run these checks by hand.

The database is back to its seeded state: 4 alert rules as V8 wrote them, 0
subscriptions, 0 notifications, test incidents removed, inbox emptied.
