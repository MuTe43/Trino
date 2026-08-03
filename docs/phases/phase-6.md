# Phase 6 — Incidents + console exploitation (2 days)

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
backend/api/src/main/resources/db/migration/V6__incidents.sql
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

timeout 20 curl -sN localhost:8080/api/v1/stream/lignes/1 | grep -q 'event: incident'
```
