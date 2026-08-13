# Phase 8 — Notifications et alertes (2.5 days)

Also read: `docs/architecture/domain-model.md`,
`docs/architecture/api-contract.md`, `docs/architecture/decisions.md` (7).

## Why this phase exists

Two use cases from the cahier des charges have no implementation and were never
scheduled: *Voyageur — recevoir des notifications* and *Administrateur — gérer
les alertes*. Decision 7 scoped notifications down to an adapter interface, but
an interface with no working channel does not deliver an actor's use case.

The scope here is deliberately narrow and honest: **two channels that genuinely
work, two that are stubs, and one screen to configure the rules.**

## Domain

New tables, one migration `V8__notifications.sql` (phase 6 takes V7 for incidents).

**Carry one index in with it.** Phase 7 built `GET /journal-connexions`, which
filters and sorts on `horodatage` and `utilisateur_id`, but that phase was
forbidden a migration so the index was never created. The table has been
gathering a row per login attempt since phase 1 and only grows. Add it here
rather than leaving it for phase 9 — this is the next migration either way:

```sql
create index idx_journal_horodatage on journal_connexion (horodatage desc);
create index idx_journal_utilisateur on journal_connexion (utilisateur_id, horodatage desc);
```

Now the notification tables:

**abonnement** — who wants to hear about what.
`id` · `utilisateur_id` FK null · `jeton_anonyme` varchar(64) null ·
`cible_type` enum (COURSE, LIGNE, GARE) · `cible_id` bigint ·
`canaux` varchar (CSV of CanalType) · `cree_at`

**Exactly one identity per row**, not "at least one":

```sql
alter table abonnement add constraint chk_abonnement_identite
  check (num_nonnulls(utilisateur_id, jeton_anonyme) = 1);

create unique index uq_abonnement_anonyme
  on abonnement (jeton_anonyme, cible_type, cible_id)
  where jeton_anonyme is not null;

create unique index uq_abonnement_utilisateur
  on abonnement (utilisateur_id, cible_type, cible_id)
  where utilisateur_id is not null;
```

The spec originally made both columns nullable with no guard and a single unique
over the anonymous half. That is wrong twice over: Postgres treats nulls as
distinct in a unique constraint, so a logged-in subscriber could duplicate the
same subscription without limit, and a row with neither identity would belong to
nobody — unreadable, undeletable, and still generating notifications.

"Exactly one" rather than "at least one" because a logged-in browser also
carries the anonymous cookie. A row holding both identities satisfies both
partial uniques, so the same person accumulates an anonymous subscription and an
account subscription to the same course and is notified twice for one event.

**Out of scope, state it rather than hide it:** an anonymous subscription is not
claimed when that visitor later logs in. The two identities never merge. Record
it in `STATE.md` as a scoped decision.

Anonymous subscription is the default: a passenger following a train has no
account. The browser holds a random `jeton_anonyme` in a cookie; that is the
whole identity. Do not require login to follow a train.

**A client may only open its own `abonne:` channel.** The `/stream` subscription
list is client-supplied, so nothing structurally stops one from naming another
subscriber's token. `StreamController` takes the token from the `X-Abonne`
header or the cookie and ignores any `abonnes=` query parameter — the client
does not get to name this channel at all. Same rule on `GET /notifications` and
`GET /abonnements/miennes`, which are scoped by token and by nothing else.

Generate the token with `SecureRandom`, 32 bytes base64url. It is a bearer
credential for one passenger's subscription list, not a display id: never log
it, never put it in a URL path or query string.

**regle_alerte** — what the administrator configures. This is *gérer les alertes*.
`id` · `evenement` enum (RETARD_SEUIL, COURSE_ANNULEE, INCIDENT_DECLARE,
INCIDENT_RESOLU) · `seuil_min` smallint null · `gravite_min` enum null ·
`canaux` varchar · `actif` boolean · `modifie_par` FK utilisateur

**notification** — what was actually emitted.
`id` · `abonnement_id` FK null · `destinataire` varchar · `canal` enum ·
`sujet` varchar · `contenu` text · `statut` enum (EN_ATTENTE, ENVOYE, ECHEC) ·
`envoye_at` · `erreur` text null
index on (`abonnement_id`,`envoye_at` desc)

```
CanalType    IN_APP, EMAIL, SMS, AFFICHAGE
CibleType    COURSE, LIGNE, GARE
```

## Engine

`MoteurNotification` subscribes to the same domain events `DiffuseurCirculation`
already publishes — do not invent a second event stream.

**Call services, never another module's repository.** The notification module
needs course, gare, ligne and utilisateur data, which is exactly the pressure
that made `IncidentService` in phase 6 reach for four foreign repositories — the
only place in the repo that breaks decision 1. Do not copy it. Every one of
those modules already exposes a service; add a method there if one is missing.
A facade layer is not required and was never the alternative. On each event it
matches active `regle_alerte` rows, resolves the concerned `abonnement` rows,
and hands one `Notification` per channel to the dispatcher.

Three rules that matter more than the plumbing:

- **Publish after commit**, exactly as phase 3 established. A notification about
  a delay that rolled back is worse than no notification.
- **Deduplicate.** A course crossing a delay threshold emits on every ping while
  it stays above it. One notification per (abonnement, evenement, course) per
  30 simulated minutes. Without this, a x20 replay sends a subscriber hundreds
  of messages in a minute and you will find out during the demo.
- **Never block ingestion.** Dispatch is asynchronous on its own executor. A
  hanging SMTP connection must not slow the delay engine.
- **Skip a deactivated account.** `abonnement` rows survive deactivation because
  a user row is never deleted (phase 7), so resolve the subscriber and drop the
  notification when `actif` is false. Anonymous subscriptions are unaffected.

## Channels

| Canal | État | Notes |
|---|---|---|
| `IN_APP` | **fonctionnel** | Pushed on the existing SSE hub, channel `abonne:{jeton}`. A bell in the public header with an unread count. |
| `EMAIL` | **fonctionnel** | SMTP to Mailpit, added to `docker-compose.yml`. Real SMTP, real message, real inbox at `localhost:8025`, no credentials, no cost. |
| `SMS` | stub | Logs the payload in a Twilio-shaped adapter. The integration point is marked; no account exists. |
| `AFFICHAGE` | existing | The station board already consumes `gare:{id}`. No new work. |

Mailpit is the point of this phase: it turns "we designed an adapter" into "here
is the email, in an inbox, on screen". That is a different sentence at a
soutenance.

## Build

```
backend/api/.../notification/domaine/{Abonnement,RegleAlerte,Notification,CanalType,CibleType,Evenement}.java
backend/api/.../notification/repo/*.java
backend/api/.../notification/service/{MoteurNotification,Dispatcheur,ServiceAbonnement,ServiceRegleAlerte}.java
backend/api/.../notification/canal/{CanalNotification,CanalInApp,CanalEmail,CanalSmsStub}.java
backend/api/.../notification/web/{AbonnementController,RegleAlerteController,NotificationController}.java
backend/api/src/main/resources/db/migration/V8__notifications.sql
docker-compose.yml                                    add mailpit
frontend/src/components/ClocheNotifications.tsx       bell + panel, public header
frontend/src/components/BoutonSuivre.tsx              "Suivre ce train"
frontend/src/app/admin/alertes/page.tsx               CRUD regle_alerte
frontend/src/lib/abonnement.ts                        anonymous token in a cookie
```

## Endpoints

```
POST   /abonnements            public  {cibleType, cibleId, canaux, email?}
DELETE /abonnements/{id}       public, scoped by jeton_anonyme
GET    /abonnements/miennes    public, by jeton
GET    /notifications          public, by jeton, paginated
GET    /regles-alerte          ADMINISTRATEUR
POST   /regles-alerte          ADMINISTRATEUR
PATCH  /regles-alerte/{id}     ADMINISTRATEUR
```

Add these to `api-contract.md` as part of the work. `EMAIL` on a subscription
requires an address; validate it, and rate-limit `POST /abonnements` to 10 per
minute per IP — it is an unauthenticated write that sends mail.

## Acceptance

```bash
docker compose up -d mailpit

JETON=$(head -c 32 /dev/urandom | base64 | tr -d '/+=' | head -c 32)
curl -s -X POST localhost:8080/api/v1/abonnements -H 'Content-Type: application/json' \
  -H "X-Abonne: $JETON" \
  -d '{"cibleType":"COURSE","cibleId":1,"canaux":["IN_APP","EMAIL"],"email":"test@exemple.tn"}'

# with the simulator running and a course crossing 5 minutes late:
curl -s -H "X-Abonne: $JETON" localhost:8080/api/v1/notifications | jq '.total'   # >= 1
curl -s localhost:8025/api/v1/messages | jq '.total'                              # >= 1

# deduplication: one notification per window, not one per ping
psql -h localhost -U trino -d trino -c \
  "select abonnement_id, evenement, course_id, canal, count(*) from notification
   group by 1,2,3,4 having count(*) > 3"  # expect no rows over a full replay

# The grouping must include course_id AND canal. One emission writes one row per
# channel, so a two-channel subscription reaches 4 rows after two legitimate
# windows and a query grouped on (abonnement, evenement) alone reports a
# deduplication failure that did not happen. The guard's key is
# (abonnement, evenement, course); the channel fan-out sits below it.

# ingestion is not blocked by a dead channel: point SMTP at a closed port,
# push a batch, and confirm /ingest/positions still answers under 200 ms
# and the notification row lands in ECHEC with its erreur populated.

ADM=$(curl -s -X POST localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"admin@sncft.tn","motDePasse":"Trino2026!"}' | jq -r .accessToken)
curl -s -o /dev/null -w '%{http_code}' -X POST localhost:8080/api/v1/regles-alerte \
  -H "Authorization: Bearer $ADM" -H 'Content-Type: application/json' \
  -d '{"evenement":"RETARD_SEUIL","seuilMin":15,"canaux":["IN_APP"],"actif":true}'  # 201

VOY=$(curl -s -X POST localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"voyageur@sncft.tn","motDePasse":"Trino2026!"}' | jq -r .accessToken)
curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $VOY" \
  localhost:8080/api/v1/regles-alerte                                              # 403
```

In a real browser: follow a train, watch the bell increment without a refresh,
then open `localhost:8025` and show the email. That sequence is the phase.
