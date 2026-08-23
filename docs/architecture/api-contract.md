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
`CONFLIT` `CLE_INGESTION_INVALIDE` `TROP_DE_REQUETES` `ERREUR_INTERNE`

`TROP_DE_REQUETES` (429, added phase 8) keeps its message, unlike the generic 400
branch: "réessayez dans une minute" is the only part a caller can act on, and a
rate limit that does not say when to come back is answered by retrying at once.

## Auth

| Method | Path | Role | Notes |
|---|---|---|---|
| POST | `/auth/login` | public | `{email, motDePasse}` -> `{accessToken, refreshToken, utilisateur}`. Writes `journal_connexion` on success AND failure. |
| POST | `/auth/refresh` | public | `{refreshToken}` -> new pair |
| POST | `/auth/logout` | auth | revokes refresh token |
| GET | `/auth/me` | auth | current user |

Access token 30 min, refresh 7 days. HS256, secret from env. Roles map to
Spring authorities as `ROLE_ADMINISTRATEUR` etc.

## Utilisateurs

`ADMINISTRATEUR` only, every verb, with both a URL rule and `@PreAuthorize`
(invariant 9). `POST /utilisateurs` carries a validated body, so without the URL
rule a forbidden caller sending a malformed payload would be told 400 —
their payload was wrong — on an endpoint they were never allowed to touch.

```
GET   /utilisateurs?page=&taille=
GET   /utilisateurs/{id}
POST  /utilisateurs
PATCH /utilisateurs/{id}
POST  /utilisateurs/{id}/mot-de-passe
```

**The server generates the password; the admin never chooses one.**
`POST /utilisateurs` takes `{email, nom, role}` and no password field at all.

```json
{
  "id": 9, "email": "test@sncft.tn", "nom": "Test",
  "role": "AGENT_CIRCULATION", "actif": true,
  "motDePasseInitial": "Xk7fRq2mHt9v"
}
```

`motDePasseInitial` appears in exactly two responses — this one and
`POST /utilisateurs/{id}/mot-de-passe` — and nowhere else, ever. Only the BCrypt
hash is stored, so it is unreadable afterwards; `UtilisateurDTO`, returned by
every other endpoint, carries no password field of any kind. Losing it means
re-issuing, not recovering.

It is called **initial**, not *temporaire*. There is no forced-change-on-first-
login flow, so *temporaire* would be a claim the system does not keep. That flow
is a flag, an endpoint and a redirect, and it would catch the four seeded demo
accounts — a phase 9 candidate, deliberately not built here. No self-service
reset, no email delivery.

`PATCH` is a partial update — `{nom?, role?, actif?}`, an absent field means
unchanged. Email is immutable. Two cases answer `409 CONFLIT`:

- deactivating **your own** account,
- changing **your own** role away from `ADMINISTRATEUR`.

Both lock you out just as thoroughly, and a demo does not survive it. The guard
compares against the authenticated principal's email, never a hardcoded id.
Re-using a taken email is `409` too.

Deactivating never deletes: `journal_connexion` references the row, and audit
trails do not get holes.

It takes effect on the **next request**, not when the access token expires.
`FiltreJwt` re-reads the account on every authenticated request and leaves the
context anonymous when `actif` is false, so a token already in someone's hands
stops working at once; `/auth/login` and `/auth/refresh` refuse it as well. The
price is one lookup per authenticated request, paid since phase 1.

Email is matched case-insensitively: `POST /utilisateurs` stores the address
lowercased and `/auth/login` normalises the same way before looking it up.
Without both halves, an account created as `Prenom.Nom@SNCFT.tn` could never be
logged into with the address the administrator typed and handed over, and every
attempt would be journalled with a null `utilisateurId` — an audit trail saying
the email matches no account when it does.

## Journal de connexions

```
GET /journal-connexions?succes=&utilisateurId=&du=&au=&page=&taille=
```

`ADMINISTRATEUR` only. `/auth/login` has been writing this table on every
attempt, successful or not, since phase 1, and nothing could read it before
phase 7.

```json
{
  "id": 812, "utilisateurId": 2, "utilisateurNom": "Agent Sousse",
  "emailTente": "agent@sncft.tn", "adresseIp": "127.0.0.1",
  "userAgent": "Mozilla/5.0 ...", "succes": true,
  "horodatage": "2026-08-11T07:41:02Z"
}
```

`utilisateurId` and `utilisateurNom` are null for an attempt on an email that
matches no account — which is most of what a failed-login list is for.

Every filter is optional and independent. `du`/`au` are plain dates bucketed in
`Africa/Tunis` (invariant 6), `au` inclusive: the bound handed to the database is
the start of the following day. When both are present, the shared `PlageDates`
window guard applies, same as the reports. Rows are sorted `horodatage` desc then
`id` desc so paging stays deterministic when several attempts share a timestamp.

Filters are composed as JPA **Specifications**, not as `:param is null` tests in
a `@Query`. That pattern binds an untyped null and Postgres answers
`could not determine data type of parameter $7` — a 500 on the default,
unfiltered view, which is the first thing anyone opens. Phase 6 shipped exactly
that bug once already.

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

The query filters — `region` and `q` on gares, `type` and `ligneId` on trains —
were declared in phase 0 and only built in phase 7, when the admin lists became
the consumer that justified them. `q` matches `nom` or `code`,
case-insensitively, on a substring. All of them are optional and a blank value
behaves as absent, so `?q=` is not a filter for the empty string. Same
Specification construction, for the same reason, as the connection journal above.

`DELETE` on a gare, a ligne or a train still referenced by a course, a desserte
or rolling stock answers `409 CONFLIT` with a message naming what points at it.
The delete is flushed inside the service so the foreign-key violation is caught
there rather than at commit — the generic `DataIntegrityViolationException`
branch reports a uniqueness conflict, which is the wrong story for an FK, and an
uncaught one is a 500 with a stack trace.

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
GET /recherche?q=&date=&region=&destination=&heureDebut=&heureFin=&page=&taille=
                                         unified search, §4.9's seven criteria.
                                         EVERY parameter is optional, q included:
                                         a caller filtering on region alone has
                                         no train number to supply, and requiring
                                         q made the other criteria unreachable.
                                         With none given it returns the service
                                         date's courses, exactly as /courses does.
```

Phase 9 added `region` (matched against any gare the course calls at),
`destination` (matched against the course's LAST `passage_gare` only, so
"Gabès" finds trains terminating there rather than passing through) and the
`heureDebut`/`heureFin` window on `departTheorique`.

The window is wall-clock time in `Africa/Tunis`, resolved against the service
date before it reaches SQL. `depart_theorique` is a `timestamptz` in UTC
(invariant 6), so binding a bare `time` would compare local hours against UTC
ones and displace every result by the network's offset.

Both bounds are always bound, defaulting to the start and end of the service
date — never to null. PostgreSQL cannot infer a type for a parameter that only
appears in `? is null`, and binding null there answered **500** `could not
determine data type of parameter` on every call, including calls passing no
window at all. The substitute bounds filter nothing: the query already restricts
rows to that `dateService`.

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
Emits deltas only. Event names: `position`, `statut`, `retard`, `incident`,
`notification`. Heartbeat comment every 15s to keep proxies from closing the
stream.

**The `abonne:` channel is never named by the client** (phase 8). The
subscription list is client-supplied, so a parameter for it would let anyone
stream another passenger's notifications with a token they guessed or stole.
`StreamController` takes the token from the `X-Abonne` header or the
`jeton_abonne` cookie and **ignores any `abonnes=` parameter**; a request that
sends one gets the ligne/gare channels it asked for and nothing else.

A token cookie alone is a valid subscription: `GET /stream` with an empty query
opens the notification channel only, which is what the bell in the public header
does on a page with no map and no board. Without one, an empty subscription is
still `400 VALIDATION_ECHOUEE`.

Frames on that channel are tagged **`abonne:moi`**, never with the real channel
name. The real name embeds the token, which reaches the browser as an `HttpOnly`
cookie precisely so page scripts cannot read it — echoing it back on every
notification would hand it over anyway. A connection carries at most its own such
channel, so the alias is unambiguous for a client routing on it.

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
GET   /incidents?statut=&gravite=&ligneId=&depuis=&page=&taille=
                                AGENT_CIRCULATION, RESPONSABLE_EXPLOITATION
GET   /incidents/ouverts        same — open ones, for a map's initial snapshot
GET   /incidents/{id}           same
POST  /incidents                same
PATCH /incidents/{id}           same — OUVERT -> EN_COURS only
POST  /incidents/{id}/resolution   RESPONSABLE_EXPLOITATION only
```

Reads are **not** public. The passenger portal learns about incidents from the
ligne SSE channel; this list carries who declared what. `ADMINISTRATEUR` is
excluded here for the same reason as the dashboards — a different duty.

Transitions are `OUVERT -> EN_COURS -> RESOLU` and `OUVERT -> RESOLU`. Anything
else is `409 CONFLIT`, named in the message.

**Resolution is its own endpoint, and that is load-bearing.** `PATCH` with
`statut: RESOLU` returns **403 for every caller, a responsable included**, with
a message pointing here. That makes it a route rule, not a role check — which is
the whole point. Expressed as "PATCH, but only a responsable may send RESOLU",
the distinction would live in the request body, which the filter chain cannot
see; `@Valid` runs during controller argument resolution, before any
`@PreAuthorize` proxy, so an agent sending a malformed body that also asked to
resolve would get `400 VALIDATION_ECHOUEE` on an operation they were never
allowed to perform. That is the phase-1 bug invariant 9 documents. Two URLs, two
pure filter-chain rules, and the specific one is declared **first** — matchers
are evaluated in order and the first match wins, so moving it below the general
`POST /incidents/**` rule silently lets any agent resolve. `IncidentSecuriteTest`
pins the ordering; it was confirmed to fail (403 → 200) when they are swapped.

Creating an incident with a `courseId` sets that course's `causeRetard` from
`TypeIncident.causeAssociee()`. The mapping **suggests**: a course that already
carries an explicitly set cause keeps it. A declaration may also carry
`causeRetard` (an explicit override, which wins) and `actionCourse`
(`ARRET_EXCEPTIONNEL` or `ANNULE`, the only two statuses an agent may set by
hand). Both require `courseId`. All of it goes through `MachineEtatCourse`,
still the only writer of `course.statut`.

At least one of `gareId`, `ligneId`, `courseId` is required. An incident
attached to nothing publishes on no channel and draws no marker.

An incident publishes an `incident` SSE event on the affected ligne channel and,
when it has one, the affected gare channel — over the connection the client
already holds, never a second transport.

A **gare-attached** incident additionally publishes on every ligne serving that
gare. Without that, a station incident reaches `gare:13` alone, and a supervision
map wanting to catch station incidents anywhere on the network has to subscribe
to all forty gare channels — an explicit list that is global in everything but
name, which is what invariant 5 exists to prevent. Fanning out server-side costs
one indexed lookup and lets that map hold five channels. It is also the right
answer for a passenger: someone watching ligne 1 should hear that a station on
ligne 1 is blocked. The gare channel still carries it, for the station board.

Payload:

```
event: incident
data: {"incidentId":2,"type":"OBSTACLE_VOIE","gravite":"MAJEURE","statut":"OUVERT",
       "description":"...","survenuAt":"2026-08-10T10:30:00Z","ligneId":1,"gareId":13,
       "courseId":null,"latitude":35.8256,"longitude":10.6084}
```

`latitude`/`longitude` are resolved server-side — the gare's coordinates, else
the course's last known position — so the passenger map and the supervision map
cannot disagree about where an incident is. Both are null for a ligne-wide
incident, which has no single point; the client lists it rather than inventing
one.

## Notifications et alertes

Two use cases of the cahier des charges that had no implementation before phase 8:
*Voyageur — recevoir des notifications* and *Administrateur — gérer les alertes*.

```
POST   /abonnements            public  {cibleType, cibleId, canaux, email?}
DELETE /abonnements/{id}       public, scoped by the caller's own identity
GET    /abonnements/miennes    public, same scoping
GET    /notifications?page=&taille=   public, same scoping
GET    /regles-alerte          ADMINISTRATEUR
POST   /regles-alerte          ADMINISTRATEUR
PATCH  /regles-alerte/{id}     ADMINISTRATEUR
```

### Identity: a cookie, not a login

**Following a train requires no account.** A passenger checking whether their
train is late has no login, and putting one in front of "Suivre ce train" would
deliver the use case to nobody who wants it.

`POST /abonnements` with no credential mints a token — `SecureRandom`, 32 bytes
base64url — and returns it as an `HttpOnly`, `SameSite=Lax`, one-year cookie
named `jeton_abonne`. **It never appears in a response body, a URL path, a query
string or a log.** It is a bearer credential for one passenger's subscription
list: whoever holds it can read their notifications and cancel their
subscriptions. A client that cannot hold a cookie may present its own token in
`X-Abonne` instead — legitimate, since the value is only ever a key to that same
caller's rows — and it is validated for shape before it reaches a uniquely
indexed `varchar(64)`.

**The `SecureRandom` property therefore holds on the cookie path only.** Any
16–64 character `[A-Za-z0-9_-]` string presented in `X-Abonne` is accepted as an
identity, so two API clients that both pick `aaaaaaaaaaaaaaaa` share one
subscription list. That is inherent to supporting the header at all, and it is
the caller's own exposure, not another subscriber's: a weak token cannot reach
rows created under a strong one.

`SameSite=Lax` and not `None`: the portal on :3000 and the API on :8080 are a
different *origin* but the same *site* (SameSite ignores the port), so Lax is
sent. `None` additionally requires `Secure`, and over plain http on localhost the
browser would drop the cookie outright — the bell would silently never bind.

All four read/write paths resolve the caller the same way: **authenticated
principal if there is one, else the token.** One rule in one place, because
`abonnement` carries exactly one identity (see domain-model) and reading an
account's subscriptions back by cookie would show their owner an empty list.

**No client in this repo takes the account path.** The portal never sends an
`Authorization` header on these endpoints, and `EventSource` cannot set one at
all, so every browser caller resolves as anonymous and every subscription the UI
creates is a token subscription. The account branch is exercised only by an API
client that sends a bearer token. It is built and tested, not reachable from the
current UI — stated so that nobody reads the rule above as describing what the
bell does.

### Subscriptions

`{cibleType, cibleId, canaux, email?}`. No identity field: whose subscription
this is comes from the caller's own credential, never from the body.

`EMAIL` requires an address. That is the one cross-field rule in the API and the
one `details[].champ` that is not a plain field path — it is reported on
`emailRequisPourCanalEmail`. The form marks its email input `required` whenever
EMAIL is ticked, so a caller normally meets the rule before sending.

**Re-subscribing is not an error**: an identity that already follows a target
gets `200` and an updated row, not `409`. "Suivre" is a button on a public page —
it gets double-clicked, pressed from a second tab, and pressed again by someone
who now wants email too. A new subscription is `201`.

`DELETE` on a subscription belonging to someone else answers `404`, not `403`:
with an id in the path and no account behind the request, distinguishing "not
yours" from "does not exist" would confirm to an enumerating caller which ids are
real.

`GET /abonnements/miennes` and `GET /notifications` answer with an empty list and
an empty page for a visitor who has no identity yet — a normal state on a public
portal, not a failure to authenticate.

```json
{
  "id": 3, "cibleType": "COURSE", "cibleId": 1361,
  "canaux": ["IN_APP", "EMAIL"], "email": "voyageur@exemple.tn",
  "creeAt": "2026-08-12T13:02:10Z"
}
```

`NotificationDTO` exposes neither `destinataire` nor `erreur`: the first is
either the reader's own address or an internal subscription reference, the second
is an SMTP diagnostic for whoever runs the system. `envoyeAt` is stamped at the
top of the dispatch rather than at the end — `CanalInApp` puts it in the SSE
frame — so it is null for a row still queued and non-null for one an adapter has
reached, success or failure alike. Either way it can be null, which is why lists
order on `id`: monotonic, never null, and on an append-only table it is the
emission order.

```json
{
  "id": 25, "evenement": "RETARD_SEUIL", "courseId": 1361, "canal": "IN_APP",
  "sujet": "Train TN101 — retard de 25 min",
  "contenu": "Le train TN101 (Tunis - Sousse - Sfax - Gabès) circule avec 25 minutes de retard.",
  "statut": "ENVOYE", "envoyeAt": "2026-08-12T13:15:23Z"
}
```

### Règles d'alerte

`ADMINISTRATEUR` only, with a URL rule **and** `@PreAuthorize` (invariant 9).
`POST` and `PATCH` carry validated bodies, so without the URL rule a forbidden
caller sending a malformed payload would be told 400 on an endpoint they were
never allowed to touch. The other three roles get 403, including
`RESPONSABLE_EXPLOITATION`: configuring what the system notifies about is
administration, not exploitation.

`{evenement, seuilMin?, graviteMin?, canaux, actif}`. `modifiePar` is not a
field — it is the authenticated administrator, and null for a rule still as V8
seeded it. `seuilMin` is required for `RETARD_SEUIL` and refused for every other
event (`409 CONFLIT`, named): a delay rule with no threshold fires on every
revision of every estimate.

`PATCH` is partial and carries **no `evenement`**: changing which event a rule
reacts to is a different rule, not an edit, and it would silently re-point every
notification the console attributes to this one. `seuilMin` sent as null leaves
the threshold alone rather than clearing it, since a `RETARD_SEUIL` row may not
have none.

**A rule's `canaux` and a subscription's `canaux` answer different questions** —
what an event is *allowed* to use, and what a subscriber *wants* — and the engine
emits on the intersection. Either saying no is a no. The four rules V8 seeds
carry all four channels, so out of the box the choice belongs entirely to the
subscriber.

### Channels

| Canal | État | Notes |
|---|---|---|
| `IN_APP` | **fonctionnel** | SSE on `abonne:{identité}`, tagged `abonne:moi`. |
| `EMAIL` | **fonctionnel** | Real SMTP to Mailpit, port `1025`. The inbox to open on screen is the web one, `localhost:8025`. |
| `SMS` | stub | `CanalSmsStub` logs a Twilio-shaped payload. No account, and no phone number anywhere in the model. |
| `AFFICHAGE` | existing | The station board already consumes `gare:{id}`. The adapter records the row as sent and emits nothing. |

Dispatch is asynchronous on its own executor, and one task per notification: a
subscriber whose EMAIL is stuck against a dead SMTP server must not hold up the
IN_APP frame that would have reached the bell instantly. Ingestion is unaffected
either way — measured at 16–36 ms for `POST /ingest/positions` with SMTP pointed
at a closed port.

**A channel that fails leaves an `ECHEC` row with `erreur` populated.** That
covers the adapter-throws path. A row whose dispatch never runs to completion —
the process is killed, or the executor's bounded queue overflows and the task is
discarded — used to stay `EN_ATTENTE` with `envoye_at` and `erreur` both null,
for ever, and `GET /notifications` showed it to the passenger in that state.
Measured in phase 8: 344 such rows left by a `taskkill /F` of the API mid-flight,
and none at all across a clean run.

**Phase 9 closed that state** with `BalayeurNotification` and V9's `cree_at`
column. `EN_ATTENTE` now means "being delivered right now": a sweep on
`ApplicationReadyEvent` fails every row older than this process's start —
an exact test, not an age heuristic, since such a row belongs to a process that
no longer exists — and a five-minute sweep fails anything that has sat past the
ten-minute delivery deadline, which catches what the startup one cannot see (a
task rejected by a saturated executor, or an error escaping the dispatcher's
`catch`). The two write different causes, so the reason is legible from the row:
*Processus interrompu avant la remise* and *Restée en attente au-delà du délai de
remise*. See RUNBOOK §11.5 to exercise it.

**Nothing retries, and that is the deliberate half.** A retry needs an idempotent
channel and a backoff, and a delay notification is worth very little by the time
one would land — the train has arrived. Recording the loss is what can be built
without a broker (decision 4).

Notifications are deduplicated: one per `(abonnement, evenement, course)` per **30
simulated minutes**, on `HorlogeCirculation` rather than the wall clock. At the
x20 replay the simulator runs, 30 simulated minutes is 90 real seconds; judged on
the wall clock the guard would let through twenty times too many. Incidents are
exempt — an incident is declared once and resolved once, so there is nothing to
suppress, and suppressing anyway would mean a second incident on the same ligne
within half an hour reached nobody.

## Tableau de bord et rapports

```
GET /tableau-bord/kpi?date=                  date REQUISE
GET /tableau-bord/retards-par-ligne?date=    date REQUISE
GET /tableau-bord/heatmap?du=&au=            du/au REQUIS. gare x tranche horaire
GET /tableau-bord/distribution-retards?du=&au=   du/au REQUIS
GET /rapports/ponctualite?du=&au=&granularite=JOUR|MOIS      du/au REQUIS
GET /rapports/incidents?du=&au=              du/au REQUIS. type x gravité, délai moyen
GET /rapports/{nom}/export?du=&au=&format=csv|xlsx           du/au REQUIS
```

**These are the only required query parameters in the API**, and the exception is
deliberate: every other `?param=` is a filter whose absence means "no filter",
whereas an analytics window has no sensible default — "today" for `date` would
make a stale bookmark silently report a different day, and an unbounded `du`/`au`
scans the whole history. There is nothing to fall back to, so the caller states
it.

A missing one is `400 VALIDATION_ECHOUEE` naming the parameter:
`details: [{"champ": "date", "probleme": "obligatoire"}]`. Until the final
documentation-reconciliation pass it was **500 `ERREUR_INTERNE`** —
`MissingServletRequestParameterException` had no branch in `ApiExceptionHandler`
and reached the `Exception` catch-all, so a plain client mistake was reported as
a server fault and logged with a stack trace. The
UI always sends the dates, so it only ever surfaced to somebody calling the API
by hand — the one caller who needs the message to be useful.
`TableauBordControllerTest` pins it.

`{nom}` is one of five, registered by name in `ServiceExport`:

| nom | Contenu |
|---|---|
| `ponctualite` | par jour : passages mesurés, à l'heure, taux, retard moyen |
| `incidents` | par type x gravité : total, résolus, délai moyen de résolution |
| `retards-par-ligne` | par ligne : courses, en retard, part, retard moyen et maximum |
| `retards-par-gare` | par gare : passages, en retard, part, retard moyen et maximum |
| `disponibilite-trains` | par (train, ligne) : programmées, réalisées, annulées, taux |

`disponibilite-trains` defines availability as the share of scheduled courses
that were not cancelled. That is the only definition this schema supports
honestly: a `Train` carries no status and no downtime (invariant 1), so there is
nothing to read a maintenance window from. The denominator includes cancelled
runs, which is what makes the ratio mean anything.

An unknown `{nom}` is a 400 `VALIDATION_ECHOUEE` listing the five available.

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
- `incidentsOuverts` / `incidentsResolus` partition the incidents **declared**
  that day, filed on `survenu_at` bucketed in `Africa/Tunis`. Not "open right
  now": every other tile is a property of the day itself, and a currently-open
  count would make a KPI for a past date change every time somebody closed an
  old incident.
- `rapports/incidents` reports `delaiResolutionMoyenH` as **null**, not 0, for a
  bucket where nothing has been resolved — an average over no rows. Zero reads
  as "resolved instantly". `resoluAt` is stamped from the wall clock, never from
  `HorlogeCirculation`: that clock belongs to the position feed and sits hours
  away under an accelerated simulator, which produced a negative time to
  resolution against a console-supplied `survenuAt`.
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
`POST /abonnements` 10 per minute per IP. Simple in-memory bucket, no library.

**All three are built** since phase 9. `LimiteurDebit` (phase 8) is a
fixed-window counter, registered three times as a `HandlerInterceptor` in
`ConfigurationWeb` — interceptors rather than the controllers, because a
controller holds no logic (invariant 7), and rather than the services, because a
service has no business knowing a caller's IP.

`/abonnements` was built first because it is the only unauthenticated endpoint
that **sends mail**: without a limit, one loop posts a thousand messages to any
address the caller names. The other two were declared here in phase 0 and stayed
unimplemented for eight phases, which is worse than not declaring them — a
contract stating a limit nobody enforces tells an integrator they are protected
when they are not.

Each is keyed on what actually identifies the abuser:

- `/ingest/*` per **ingest key**, not per IP — several GPS boxes behind one NAT
  would otherwise share a budget. The key is hashed before it becomes a map key,
  because that string is held in memory and named in any heap dump. A request
  with no key at all is not counted: `FiltreCleIngestion` rejects it, and
  counting it would let an unauthenticated caller consume a real producer's
  allowance.
- `/auth/login` per **IP**, not per submitted email — credential stuffing tries
  many accounts from one place, and keying on the email would give every guessed
  address its own fresh budget.

Fixed window rather than sliding or a token bucket: the difference between 10 per
minute and up to 20 across a window boundary does not change whether the endpoint
can be abused, and the simpler structure has no timer and no eviction thread.
