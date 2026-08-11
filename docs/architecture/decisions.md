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
