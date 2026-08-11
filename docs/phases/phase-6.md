# Phase 6 — Incidents + console exploitation (2 days)

Also read: `docs/architecture/domain-model.md` (Role enum, incident tables) and
`docs/architecture/api-contract.md` (Incidents section).

## Three things to close first

1. **Confirm the multi-channel SSE frame against live traffic.** Phase 5 proved
   it on the serialized body in `StreamControllerTest` but never watched a real
   delta carry two channels, because the simulated day was exhausted. Incidents
   publish on those same channels, so if the routing is wrong this phase
   inherits the bug silently. Regenerate a service day, then watch
   `/stream?lignes=1&gares=…` for a frame whose `canaux` holds both.

2. **Make the DB-backed tests fail rather than skip.** `CoherenceSeedTest` and
   `AnalytiqueRepositoryTest` currently self-skip when Postgres is unreachable,
   so the suite reports green having tested nothing. Invert the default: require
   the database, and let an explicit opt-out flag skip them. Fail closed.

3. **A minimal public header.** The portal currently has no chrome at all: `/`
   opens on the map, and `/connexion`, `/affichage/{gareId}` and the gare pages
   are reachable only by typing a URL. Map-first is correct and stays — this is
   about the three routes with no entry point.

   A slim bar over the full-bleed map, hairline bottom border, no shadow: the
   SNCFT wordmark set as type in `--sncft-bleu` (not the raster logo), the
   existing search, and a discreet "Espace exploitation" link to `/connexion` on
   the right. Nothing else — no menu, no hamburger. On a gare page, add a link
   to that gare's board.

   `/affichage/{gareId}` keeps **no** header: it is a kiosk and stays chrome-free.

Your #3 priority. **This is the cut line.** If you reach the last week without
phases 0-5 fully working, skip this and go straight to phase 7. A polished
subset defends better than a broken superset.

## Goal

Agents declare and manage incidents; the responsable supervises traffic and sees
incidents on the map; delay causes get attributed.

## Build

```
backend/api/.../exploitation/domaine/{Incident,TypeIncident,Gravite,StatutIncident}.java
backend/api/.../exploitation/repo/IncidentRepository.java
backend/api/.../exploitation/service/IncidentService.java
backend/api/.../exploitation/web/IncidentController.java
backend/api/.../exploitation/dto/*.java
backend/api/src/main/resources/db/migration/V7__incidents.sql
frontend/src/app/exploitation/layout.tsx           role-gated shell
frontend/src/app/exploitation/trafic/page.tsx      supervision map
frontend/src/app/exploitation/incidents/page.tsx   list + filters
frontend/src/components/FormulaireIncident.tsx
```

## Behaviour

- `AGENT_CIRCULATION` can declare an incident, change a course status to
  `ARRET_EXCEPTIONNEL` or `ANNULE`, and set a `causeRetard`. Nothing else.
- `RESPONSABLE_EXPLOITATION` can do all of that plus resolve incidents and see
  every dashboard.
- Declaring an incident linked to a `courseId` sets that course's `causeRetard`.
  Route this through `MachineEtatCourse` — invariant, phase 3 established it as
  the only status writer, and a manual cancellation is still a status change.
- New incidents publish an `incident` SSE event on the affected ligne channel,
  so the passenger map picks them up without a poll.
- Incident markers on the supervision map, at the gare or at the course's last
  known position.

## Rules

- Reuse the SSE hub from phase 3. Do not open a second transport.
- The incident form is the only place in the app with a non-trivial form. Use
  plain controlled inputs and native validation plus server-side Bean Validation.
  No form library.
- Status transitions on incidents are constrained: `OUVERT -> EN_COURS -> RESOLU`,
  and `OUVERT -> RESOLU` directly. Reject anything else with `CONFLIT`.

### Resolution is its own endpoint

```
PATCH /incidents/{id}              AGENT_CIRCULATION, RESPONSABLE_EXPLOITATION
POST  /incidents/{id}/resolution   RESPONSABLE_EXPLOITATION only
```

Do **not** put the resolution role check inside `PATCH`. The distinction would
live in the request body, which the filter chain cannot see, so an agent sending
a malformed body that also asked to resolve would get `400 VALIDATION_ECHOUEE`
instead of `403` — the exact failure invariant 9 documents. A separate URL keeps
both rules pure filter-chain rules. Resolving is a state transition, not a field
edit, and it is the responsable's use case in the cahier des charges.

Add both to `api-contract.md` as part of the work.

### The KPI tiles stop being zero

Phase 5 hardcoded `incidentsOuverts` and `incidentsResolus` to `0` with a
`// phase 6` comment, and deferred `/rapports/incidents`. Wire all three now:
the counts come from the same repository query, and `ServiceExport` was written
generic over report name precisely so this is one method plus one map entry.
Remove the contingency note about deleting the tiles — they work.

### Incident type maps to a delay cause

`TypeIncident.causeAssociee()` — one lookup, recorded in `domain-model.md`.
The mapping **suggests**; it never overwrites. If a course already carries an
explicitly set `causeRetard`, linking an incident leaves it alone. An agent who
named the cause knows more than a lookup table.

## Acceptance

```bash
AGENT=$(curl -s -X POST localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"agent@sncft.tn","motDePasse":"Trino2026!"}' | jq -r .accessToken)

ID=$(curl -s -X POST localhost:8080/api/v1/incidents -H "Authorization: Bearer $AGENT" \
  -H 'Content-Type: application/json' \
  -d '{"type":"DEFAUT_SIGNALISATION","description":"Signal bloqué","gravite":"MOYENNE",
       "ligneId":1,"courseId":1,"impact":"Ralentissement","survenuAt":"'$(date -u +%FT%TZ)'"}' \
  | jq -r .id)
test -n "$ID"

psql -h localhost -U trino -d trino -c \
  "select cause_retard from course where id = 1"    # DEFAUT_SIGNALISATION mapped to a CauseRetard

curl -s -o /dev/null -w '%{http_code}' -X PATCH localhost:8080/api/v1/incidents/$ID \
  -H "Authorization: Bearer $AGENT" -H 'Content-Type: application/json' \
  -d '{"statut":"RESOLU"}'    # expect 403 — only the responsable resolves

timeout 20 curl -sN localhost:8080/api/v1/stream/lignes/1 | grep -qE 'event: ?incident'
# Spring's SseEmitter writes `event:incident` with no space, which is valid SSE —
# the space after the colon is optional and a client strips at most one. The grep
# tolerates both; do not change the emitter to satisfy a test.
```
