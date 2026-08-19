# Decisions

Short records. Read this when you are tempted to change something structural.

## 1. Modular monolith, not microservices

One Spring Boot app with package-level module boundaries
(`referentiel`, `circulation`, `exploitation`, `iam`, `analytique`). Modules
talk through service interfaces, never through each other's repositories.

Rejected microservices: a solo intern with under a month cannot operate a
distributed system, and the spec's availability target does not require one.
The package boundaries mean a later split is mechanical.

## 2. The simulator is a separate process behind an HTTP contract

The alternative — a `@Scheduled` bean generating positions inside the domain
layer — is faster to write and architecturally dead. It makes the whole system
a mock with no migration path to real hardware.

By putting the producer outside and defining `POST /ingest/positions`, Trino
consumes a GPS feed from an authenticated external source. Replacing the
simulator with real AVL equipment is a configuration change. This is the
strongest claim in the soutenance; do not collapse it for convenience.

## 3. SSE, not WebSocket

Traffic is server -> client only. SSE gives automatic reconnection with
`Last-Event-ID`, survives proxies, and needs no sub-protocol. WebSocket would
add a handshake, a heartbeat protocol, and STOMP or a hand-rolled frame format
for nothing. Revisit only if the client ever needs to push.

## 4. No Redis

Redis was in the original sketch for hot state and cross-instance pub/sub. The
deliverable is a single local instance, so there is nothing to fan out between.
`EtatCirculationStore` is a `ConcurrentHashMap` behind an interface; swapping
in Redis later is one class.

Say this out loud in the report — "we deliberately did not add infrastructure
we could not justify" is a stronger engineering statement than an unused Redis
container.

## 5. No PostGIS

The only geometry operation needed is progress along a polyline: given a
distance travelled, return a lat/lon. That is haversine plus linear
interpolation, about 60 lines in `GeometrieLigne`. The spec contains no spatial
query. PostGIS would cost setup time and buy nothing.

## 6. Delay propagates forward, ETA is speed-based

When a course is N minutes late at station K, every downstream `passage_gare`
inherits N minutes unless the train makes up time. ETA for the next station is
`(pk_suivante - avancement) / vitesse_moyenne_recente`, floored at the
theoretical time. No machine learning, no historical regression. It is honest
and it is explainable in a defence.

## 7. Two working notification channels, two honest stubs

**Revised at phase 8.** The original decision scoped notifications to an adapter
interface with no working channel. That was wrong: *recevoir des notifications*
is a use case of the cahier des charges, and an interface nobody can demonstrate
does not deliver it. Ranking it last among four priorities is not the same as
authorising its removal.

`CanalNotification` with an async dispatch. `IN_APP` over the existing SSE hub
and `EMAIL` over SMTP to Mailpit both genuinely work — a real message in a real
inbox, no credentials, no cost. `SMS` stays a Twilio-shaped stub that logs, and
push is not implemented. Both gaps are stated rather than hidden.

Four half-working integrations would still be worse than this. Zero working ones
was worse than either.

## 8. Availability is designed for, not claimed

The spec asks for 99.9% and 24/7. Neither is testable in this delivery. What is
actually implemented: stateless API layer, Spring Actuator health and readiness
probes, graceful degradation when the position feed stops (courses move to
`ARRET_EXCEPTIONNEL` rather than freezing on stale data), and Postgres backup
documented as a `pg_dump` cron. Report it as design intent with the gap stated.

## 9. Resolving an incident is a separate endpoint, not a PATCH field

`PATCH /incidents/{id}` is open to an agent; resolving is the responsable's. The
obvious shape — one PATCH, with a role check on `statut: RESOLU` — puts the
distinction in the request body, and the filter chain cannot see a body.
`@Valid` runs during controller argument resolution, before the AOP proxy behind
`@PreAuthorize` exists, so an agent sending a malformed payload that also asked
to resolve would be told their payload was malformed on an operation they were
never allowed to perform. That is the phase-1 bug invariant 9 exists to
document, and it is invisible to the compiler and to any test that sends a valid
body.

So `POST /incidents/{id}/resolution` carries its own URL rule, and PATCH refuses
`RESOLU` for **everyone** — a responsable included. That last part is what keeps
it a route rule rather than a role check smuggled back in: no role information
enters the decision, so the 400-instead-of-403 case cannot reappear.

The cost is one more endpoint and an error message that has to explain where to
go. `OperationInterditeException` exists for that: Spring's `AccessDeniedException`
is answered with a fixed "Accès refusé.", which would leave the caller with a 403
and no idea the operation exists elsewhere.

The matcher order is load-bearing — the resolution rule must precede the general
`POST /incidents/**` rule, or any agent may resolve. `IncidentSecuriteTest` pins
it, and was confirmed to fail (403 → 200) with the two swapped.

## 10. The server generates account passwords, and calls them *initial*

The admin console never lets an administrator choose a password for someone
else. The server generates one, returns it exactly once — in the response to
`POST /utilisateurs` or `POST /utilisateurs/{id}/mot-de-passe` — and stores only
the BCrypt hash. There is no way to read it back, by design: an endpoint that
could would be an endpoint that leaks every account.

An admin-chosen password is worse than it looks. It travels to the new user over
whatever channel the admin happens to use, it tends to be a pattern rather than a
secret, and it is the same person's habit reused across the accounts they create.
A generated one is none of those. The alphabet drops the ambiguous glyphs
(`O 0 o l I 1`) because a human reads this aloud and retypes it.

The field is `motDePasseInitial`, not `motDePasseTemporaire`. *Temporaire*
promises a forced change on first login — a flag, an endpoint, a redirect, and a
migration touching the four seeded demo accounts, which is a login path breaking
mid-demo for no benefit inside this timeline. Naming it *temporary* while
enforcing nothing would be a claim the system does not keep, and the next person
to read the field name would trust it. It is a phase 9 candidate, recorded rather
than half-built.

The consequence to accept: losing the string means re-issuing, not recovering.
That is the correct trade and the UI says so where it shows the password.

## 11. An admin cannot lock themselves out, and the guard is the principal

`PATCH /utilisateurs/{id}` refuses, with `409 CONFLIT`, an administrator
deactivating their own account or moving their own role off `ADMINISTRATEUR`.
Both end the same way: nobody can administer anything, and the only fix is a
manual `UPDATE` against the database — during a demo, in front of the people
being demoed to.

The guard compares the target against the **authenticated principal's email**,
never a hardcoded id. `utilisateur 1` is only the seeded demo admin; a second
administrator created through this very console would be unguarded, and the one
account most likely to be experimented with is the newest. The acceptance script
reads its own id from `/auth/me` for the same reason.

Deactivating someone else never deletes them. `journal_connexion` holds a plain
`utilisateur_id` FK, and an audit trail with holes in it is not an audit trail.
`refresh_token` holds one too, so the row is doubly undeletable — which is the
design working, not an obstacle to route around.

Deactivation takes effect on the next request. `FiltreJwt` re-reads the account
on every authenticated request and leaves the context anonymous when `actif` is
false, so an access token already issued stops working immediately rather than
lasting out its 30 minutes. That costs one lookup per request and has since
phase 1; it is worth writing down because the cheaper design — trusting the
token's claims until it expires — is the one a reader assumes, and it would make
"désactiver" a promise the console could not keep for half an hour.

## 12. A cookie is the whole identity of a passenger who follows a train

Requiring an account to press "Suivre ce train" would deliver *recevoir des
notifications* to nobody who actually wants it. A passenger checking whether
their train is late has no login and will not make one for a single journey. So
anonymous subscription is the default path, not a fallback: the server mints a
`SecureRandom` token on the first `POST /abonnements` and returns it as an
`HttpOnly` cookie. That is the entire identity.

**It is a bearer credential, and treated as one.** Whoever holds it can read that
passenger's notifications and cancel their subscriptions. It therefore never
appears in a response body, a URL path, a query string or a log line — and the
`abonne:` SSE channel is derived server-side from it rather than named by the
client, because the subscription list on `/stream` is client-supplied and a
parameter for that channel would let anyone stream anybody's notifications. For
the same reason the frame is tagged with the alias `abonne:moi`: the real channel
name embeds the token, and echoing it back on every notification would undo the
`HttpOnly` cookie it arrived in.

`SameSite=Lax`, not `None`. The portal on :3000 and the API on :8080 are a
different origin but the same *site* — SameSite ignores the port — so Lax is sent
on these requests. `None` would additionally require `Secure`, which over plain
http on localhost means the browser drops the cookie and the bell silently never
binds. This is worth recording because the symptom of getting it wrong is nothing
at all.

**The two identities never merge.** A row carries exactly one of `utilisateur_id`
and `jeton_anonyme`, so a visitor who subscribes anonymously and later signs in
does not inherit those subscriptions. Claiming them would mean a login-time
migration and a rule for what happens when both identities already follow the
same train; neither is worth building inside this timeline, and the alternative —
allowing both on one row — is worse, because a signed-in browser still carries
the anonymous cookie and the same person would be notified twice for one event.
Stated rather than hidden.

**Deduplication is measured in simulated minutes.** One notification per
`(abonnement, evenement, course)` per 30 minutes of `HorlogeCirculation`, not of
wall-clock time. At the x20 replay the demo runs on, 30 simulated minutes is 90
real seconds; a wall-clock window would let through twenty times too many, which
during a soutenance is a subscriber receiving hundreds of messages in a minute.
At acceleration 1 the two clocks are identical, so nothing about real hardware
changes. The state is in memory, like `EtatCirculationStore` and for the same
reason: a restart costs at most one duplicate.

---

## 13. Rétention : ce que l'on garde, et pendant combien de temps (phase 9)

Trois tables ne cessaient de croître et rien ne les balayait. Aucune n'est
partitionnée — hors périmètre pour une livraison locale — mais chacune a
maintenant une fenêtre énoncée, ce qui est la moitié qui manquait.

**`refresh_token` : purgé sept jours après expiration.** Chaque connexion depuis
la phase 1 laissait une ligne que rien ne supprimait. `PurgeJetons` s'exécute à
03:30, après que `GenerateurCourses` a matérialisé la journée à 03:00, et efface
les lignes expirées depuis plus longtemps que la durée de vie d'un jeton. Une
ligne révoquée n'est *pas* supprimée immédiatement : entre la révocation et
l'expiration, elle est la seule preuve qu'un jeton présenté avait bien été émis
puis retiré. La supprimer transforme « ce jeton a été révoqué » en « ce jeton n'a
jamais existé » — la même réponse qu'un jeton forgé, sur la piste d'audit que
quelqu'un consulte précisément parce qu'il soupçonne la différence.

**`notification` : conservée, mais plus jamais bloquée en `EN_ATTENTE`.** La
table garde toutes ses lignes — c'est la trace de ce qui a été émis, et son
volume est proportionnel aux abonnements, pas au flux GPS. Ce qui a changé est
l'état : `EN_ATTENTE` signifie désormais « en cours de remise à cet instant ».
`BalayeurNotification` bascule en `ECHEC`, avec une cause écrite, toute ligne
plus ancienne que le processus qui pourrait la traiter. Mesuré en phase 8 : un
`taskkill /F` de l'API laissait **344** lignes qu'aucun processus n'aurait jamais
reprises. Rien ne réessaie : un réessai suppose un canal idempotent et un
back-off, et une notification de retard ne vaut plus grand-chose quand le réessai
aboutirait — le train est arrivé. Enregistrer la perte est la moitié honnête, et
c'est celle qui se construit sans courtier de messages (décision 4).

**`position_course` : croissance non bornée, assumée et chiffrée.** Une course
émet un ping toutes les cinq secondes. À 320 courses simultanées cela fait
environ 230 000 lignes par journée de service. La table est l'historique
append-only que lisent les rapports et la relecture de trace, jamais le chemin
chaud (`EtatCirculationStore` est en mémoire). Fenêtre recommandée en
production : **90 jours**, par un `delete` mensuel sur `date_service`, ou une
partition par mois si le volume le justifie. Non construit ici : une démonstration
locale ne vit pas assez longtemps pour l'exercer, et un balayage non testé qui
supprime de l'historique est plus dangereux que son absence.

**L'état chaud est borné par construction, pas par énumération.**
`EtatCirculationStore` n'était vidé qu'à `TERMINUS_ATTEINT`. Ce n'est pas une
borne : une course finissant `ANNULE` gardait sa fenêtre, une course laissée en
`ARRET_EXCEPTIONNEL` aussi, et au basculement de 03:00 toute la veille également.
`DetecteurSilence` garde désormais l'ensemble vivant et oublie le reste — la
propriété réellement voulue, qu'aucun nouveau statut ajouté à l'énumération ne
peut périmer.

---

## 14. Où se termine TLS (phase 9)

Rien n'écoute en HTTPS dans cette livraison, et c'est un choix.

Terminer TLS localement suppose un certificat auto-signé, et la politique de
cookies de la décision 12 a été réglée pour du HTTP en clair sur `localhost` : le
jeton d'abonné est posé en `SameSite=Lax` sans `Secure`. Basculer casserait
silencieusement la cloche de notifications, c'est-à-dire l'étape de démonstration
qui transforme « nous avons conçu un système de notifications » en « voici le
courriel ».

La couche applicative est déjà agnostique, et c'est cela qui compte pour le
rapport. En production, TLS se termine sur un proxy inverse devant l'API et le
web ; trois valeurs de configuration suivent, aucune ligne de code :

- le proxy pose `X-Forwarded-Proto: https`, que Boot lit pour construire ses URL
  absolues (`server.forward-headers-strategy=framework`) ;
- `refreshToken` et `jeton_abonne` passent en `Secure` ;
- `TRINO_CORS_ORIGINES` reçoit les origines `https://` réelles.

L'application ne construit aucune URL absolue vers elle-même dans ses réponses et
ne compare jamais un schéma, ce qui est la raison pour laquelle la bascule tient
en trois réglages plutôt qu'en une relecture.

