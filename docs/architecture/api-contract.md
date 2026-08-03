# API contract

Base: `/api/v1`. All responses JSON. All timestamps ISO-8601 with offset.
Server stores UTC; the frontend renders `Africa/Tunis`.

## Error envelope

Every 4xx/5xx returns exactly this shape, produced by one
`@RestControllerAdvice`. No endpoint invents its own error format.

```json
{
  "horodatage": "2026-08-03T10:22:31Z",
  "statut": 400,
  "code": "VALIDATION_ECHOUEE",
  "message": "La requête est invalide.",
  "details": [{ "champ": "ligneId", "probleme": "obligatoire" }]
}
```

Codes: `VALIDATION_ECHOUEE` `NON_AUTHENTIFIE` `ACCES_REFUSE` `INTROUVABLE`
`CONFLIT` `CLE_INGESTION_INVALIDE` `ERREUR_INTERNE`

## Auth

| Method | Path | Role | Notes |
|---|---|---|---|
| POST | `/auth/login` | public | `{email, motDePasse}` -> `{accessToken, refreshToken, utilisateur}`. Writes `journal_connexion` on success AND failure. |
| POST | `/auth/refresh` | public | `{refreshToken}` -> new pair |
| POST | `/auth/logout` | auth | revokes refresh token |
| GET | `/auth/me` | auth | current user |

Access token 30 min, refresh 7 days. HS256, secret from env. Roles map to
Spring authorities as `ROLE_ADMINISTRATEUR` etc.

## Référentiel

Read is public. Write is `ADMINISTRATEUR` only.

```
GET    /gares?region=&q=&page=&taille=
GET    /gares/{id}
POST   /gares          PUT /gares/{id}          DELETE /gares/{id}
GET    /lignes         GET /lignes/{id}         (same write verbs)
GET    /lignes/{id}/desserte        ordered stops with pk and offsets
GET    /trains?type=&ligneId=       GET /trains/{id}   (same write verbs)
```

Paged responses: `{contenu: [...], page, taille, total}`.

## Circulation — read

```
GET /courses?date=&ligneId=&gareId=&statut=&type=&q=&page=&taille=
GET /courses/{id}
GET /courses/{id}/passages
GET /courses/{id}/positions?depuis=      history, for the trace replay
GET /gares/{id}/departs?limite=20        next departures, for the station board
                                         ordered by departEstime, NOT by
                                         departTheorique — a delayed train
                                         must fall down the board
GET /recherche?q=                        unified: numéro, ligne, gare, destination
```

`CourseResumeDTO`:
```json
{
  "id": 4821, "numeroTrain": "DR201", "nomTrain": "Le Sahel",
  "type": "GRANDES_LIGNES", "ligne": {"id": 1, "nom": "Tunis - Gabès"},
  "sens": "ALLER", "statut": "RETARDE",
  "retardMin": 12, "classeRetard": "R10", "causeRetard": "SIGNALISATION",
  "departTheorique": "2026-08-03T06:10:00Z",
  "arriveeTheorique": "2026-08-03T12:40:00Z",
  "position": {"latitude": 35.8256, "longitude": 10.6084, "vitesseKmh": 96},
  "garePrecedente": {"id": 12, "nom": "Sousse"},
  "gareSuivante": {"id": 13, "nom": "Msaken"},
  "etaSuivante": "2026-08-03T08:14:00Z"
}
```

`PassageDTO` — one per stop, returned by `/courses/{id}/passages` and by
`/gares/{id}/departs`. Carries all three times from spec 4.5:

```json
{
  "ordre": 4, "gare": {"id": 13, "nom": "Msaken"}, "quai": "2",
  "arriveeTheorique": "2026-08-03T14:35:00Z",
  "arriveeEstimee":   "2026-08-03T14:44:00Z",
  "arriveeReelle":    null,
  "departTheorique":  "2026-08-03T14:37:00Z",
  "departEstime":     "2026-08-03T14:46:00Z",
  "departReel":       null,
  "retardMin": 9, "classeRetard": "R5", "franchi": false
}
```

`franchi` is `arriveeReelle != null`. The client renders the real time for
franchi stops and the estimated time for the rest — it never adds `retardMin`
to a theoretical time itself.

## Circulation — live (SSE)

```
GET /stream/lignes/{ligneId}     text/event-stream
GET /stream/gares/{gareId}       text/event-stream
```

Public, no auth (the passenger portal and station boards are anonymous).
Emits deltas only. Event names: `position`, `statut`, `retard`, `incident`.
Heartbeat comment every 15s to keep proxies from closing the stream.

```
event: position
data: {"courseId":4821,"latitude":35.83,"longitude":10.61,"vitesseKmh":96,"avancementKm":143.2,"etaSuivante":"2026-08-03T08:14:00Z"}

event: retard
data: {"courseId":4821,"retardMin":12,"classeRetard":"R10","causeRetard":"SIGNALISATION",
       "passagesRevises":[{"ordre":4,"arriveeEstimee":"2026-08-03T14:44:00Z","retardMin":9},
                          {"ordre":5,"arriveeEstimee":"2026-08-03T15:56:00Z","retardMin":6}]}
```

`passagesRevises` carries only the stops whose estimate actually moved, so the
station board can update its own row without refetching the course. Still a
delta — never the full passage list.

Never emit a full course list on this channel. The client fetches the initial
snapshot over REST, then applies deltas.

## Ingestion

```
POST /api/v1/ingest/positions        header X-Ingest-Key
GET  /api/v1/ingest/courses-du-jour  header X-Ingest-Key
```

This is the seam between Trino and the position source. The simulator is one
implementation; real AVL hardware would be another. Nothing else about the
system knows the simulator exists.

Request body is a batch:
```json
{"pings":[
  {"courseId":4821,"horodatage":"2026-08-03T08:02:15Z",
   "latitude":35.8256,"longitude":10.6084,"vitesseKmh":96}
]}
```
Response `202 Accepted` with `{"acceptes": 42, "rejetes": 0}`. Rejects a ping
whose course is `ANNULE` or `TERMINUS_ATTEINT`. Max 500 pings per batch.

`courses-du-jour` returns each active course with its ligne `trace`, its
desserte with `pk_km`, and its theoretical times — everything the producer
needs to know where a train should be.

## Incidents

```
GET   /incidents?statut=&gravite=&ligneId=&depuis=
POST  /incidents                AGENT_CIRCULATION, RESPONSABLE_EXPLOITATION
PATCH /incidents/{id}           same, plus status transitions
```

Creating an incident with a `courseId` may set that course's `causeRetard`.

## Tableau de bord et rapports

```
GET /tableau-bord/kpi?date=
GET /tableau-bord/retards-par-ligne?date=
GET /tableau-bord/heatmap?du=&au=            gare x tranche horaire
GET /rapports/ponctualite?du=&au=&granularite=jour|mois
GET /rapports/incidents?du=&au=
GET /rapports/{nom}/export?du=&au=&format=csv|xlsx
```

KPI payload: `trainsEnCirculation`, `nbRetards`, `retardMoyenMin`,
`tauxPonctualite`, `incidentsOuverts`, `incidentsResolus`, `trainsAnnules`,
`voyageursImpactes` (estimated as sum of capacity on delayed courses — label it
as an estimate in the UI, it is not a real measurement).

Export returns the file with `Content-Disposition: attachment`. CSV and XLSX
only. PDF is out of scope unless phase 7 has time left.

## Rate limits

`/auth/login` 10 per minute per IP. `/ingest/*` 120 per minute per key.
Simple in-memory bucket, no library.
