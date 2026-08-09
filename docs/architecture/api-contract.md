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

`PassageDTO` — one per stop, returned by `/courses/{id}/passages`. Carries all
three times from spec 4.5:

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

`DepartGareDTO` — returned by `/gares/{id}/departs`. A `PassageDTO` alone
cannot render a station board: it carries times but no train identity, and a
board without a train number and a destination is not a board.

```json
{
  "courseId": 4821, "numeroTrain": "DR201", "nomTrain": "Le Sahel",
  "type": "GRANDES_LIGNES", "destination": "Gabès", "quai": "2",
  "departTheorique": "2026-08-03T14:37:00Z",
  "departEstime":    "2026-08-03T14:46:00Z",
  "departReel":      null,
  "statut": "RETARDE", "retardMin": 9, "classeRetard": "R5"
}
```

`destination` is the `gare` of the course's last `passage_gare`, resolved
server-side — the board must not have to fetch the stop list per train.

## Circulation — live (SSE)

```
GET /stream?lignes=1,2,3&gares=7 text/event-stream   multiplexé
GET /stream/lignes/{ligneId}     text/event-stream
GET /stream/gares/{gareId}       text/event-stream
```

Public, no auth (the passenger portal and station boards are anonymous).
Emits deltas only. Event names: `position`, `statut`, `retard`, `incident`.
Heartbeat comment every 15s to keep proxies from closing the stream.

**`/stream` is the one a browser client should use** (added phase 5). One
connection per client carrying every channel it asked for. A browser allows
about six connections per origin over HTTP/1.1 and shares them with every REST
call to the same origin; one socket per ligne put the map at five of that budget
on the default view. Measured against the API: at six channels the next REST
request is never served and the app appears to hang. On one multiplexed
connection, twelve channels cost nothing.

This does not weaken invariant 5. The subscription list is explicit, so a client
still receives only what it named — there is no way to ask for everything. An
empty subscription is refused with `400 VALIDATION_ECHOUEE` rather than opening
a stream that can never deliver anything.

Frames on `/stream` are wrapped so each carries the channels it belongs to:

```
event: position
data: {"canaux":["ligne:1","gare:7"],"donnees":{"courseId":4821,"latitude":35.83, ...}}
```

`canaux` is a **list**, and that is load-bearing. A course publishes to its
ligne and to every gare it has not yet cleared, so one delta legitimately
concerns several of a client's channels at once. The frame is sent once, tagged
with all of them. Tagging it with only the first match is silent data loss: the
client routes on this field, so a page holding both the map (`ligne:1`) and a
station board (`gare:7`) would see the delta delivered to the map and never to
the board — no error, no reconnect, just a table that stops moving.

The two per-path endpoints keep the bare payload shown below and stay for the
kiosk board, which watches exactly one gare and should stay simple.

No frame carries an `id`, so there is no replay and a reconnecting client cannot
resume with `Last-Event-ID`; it refetches its snapshot over REST. Deltas emitted
while a client is reconnecting are lost by design. The client therefore hands
over rather than cuts when its channel set changes — it keeps the old connection
delivering until the replacement is open.

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
GET /tableau-bord/distribution-retards?du=&au=   courses par tranche de retard
GET /rapports/ponctualite?du=&au=&granularite=JOUR|MOIS
GET /rapports/incidents?du=&au=              phase 6
GET /rapports/{nom}/export?du=&au=&format=csv|xlsx
```

All of these are `RESPONSABLE_EXPLOITATION` and **only** that role.
`ADMINISTRATEUR` gets 403: it administers the référentiel, the accounts and the
connection log, and reading operational analytics is a different duty.

KPI payload: `trainsEnCirculation`, `nbRetards`, `retardMoyenMin`,
`tauxPonctualite`, `passagesMesures`, `incidentsOuverts`, `incidentsResolus`,
`trainsAnnules`, `voyageursImpactes` (estimated as sum of capacity on delayed
courses — label it as an estimate in the UI, it is not a real measurement).

- `trainsEnCirculation` is the day's courses that were not cancelled, not the
  number moving at this instant — the latter reading reports zero for every past
  date and makes the card useless for anything but today.
- `nbRetards` counts courses 5+ minutes late, the same cut as `ClasseRetard`.
- `tauxPonctualite` counts **only stops actually reached**
  (`arrivee_reelle is not null`). A stop still ahead of its train carries
  `retard_min = 0`; counting it would score the untravelled rest of the day as
  on time, so punctuality would start each morning at 100 % and sink as reality
  arrived. `passagesMesures` is that denominator, and it is 0 early in a service
  day — render "—" then, never "0 %".
- `distribution-retards` is not in the phase-5 query list but its Charts section
  asks for a delay histogram and nothing else carries that distribution. Buckets
  are computed by `ClasseRetard.de`, never by a CASE in SQL, so the thresholds
  keep one definition.
- Hours in the heatmap are bucketed in `Africa/Tunis`, not UTC (invariant 6).

Export returns the file with `Content-Disposition: attachment`, filename
`trino-{nom}-{du}-{au}.{ext}`. CSV uses `;`, a UTF-8 BOM and decimal commas so a
French-locale Excel opens it correctly. CSV and XLSX only; PDF is out of scope
unless phase 7 has time left.

The export is written synchronously to the response, deliberately **not** as a
`StreamingResponseBody`. Async turns the request over to a second dispatch, and
`FiltreJwt` (an `OncePerRequestFilter`) skips async dispatches while Spring
Security's `AuthorizationFilter` does not — so the role rule denies an
unauthenticated re-dispatch after `text/csv` is already committed. The file
still downloads and the server logs three ERROR stack traces per export.

## Rate limits

`/auth/login` 10 per minute per IP. `/ingest/*` 120 per minute per key.
Simple in-memory bucket, no library.
