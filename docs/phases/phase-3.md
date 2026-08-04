# Phase 3 — Temps réel & retards (3 days)

Also read: `docs/architecture/domain-model.md` (state machine),
`docs/architecture/api-contract.md` (SSE section),
`docs/architecture/decisions.md` (records 3, 4, 6).

## Goal

The delay engine. Every ingested ping resolves which stations have been passed,
stamps real times, computes and propagates delay, runs the state machine, and
publishes a delta to the right SSE channels.

## Build

```
backend/api/.../circulation/service/EtatCirculationStore.java   interface + InMemory impl
backend/api/.../circulation/service/MoteurRetard.java
backend/api/.../circulation/service/MachineEtatCourse.java
backend/api/.../circulation/service/CalculateurEta.java
backend/api/.../circulation/service/DetecteurSilence.java       @Scheduled, 30s
backend/api/.../circulation/evenement/{EvenementPosition,EvenementStatut,EvenementRetard}.java
backend/api/.../diffusion/HubSse.java
backend/api/.../diffusion/web/StreamController.java
backend/api/.../circulation/web/CourseController.java           REST reads + /recherche
backend/api/.../circulation/dto/{CourseResumeDTO,PassageDTO,PositionDTO}.java
backend/api/src/main/resources/db/migration/V5__index_circulation.sql
```

No new tables this phase. V5 is indexes only, sized against the queries you
actually wrote.

## The engine, in order

On each ping in `IngestionService`:

1. `EtatCirculationStore.mettreAJour(courseId, position)` — memory, not DB.
2. `MoteurRetard.traiter(course, position)`:
   - find the last `passage_gare` whose `pk_km <= avancement_km` and whose
     `arrivee_reelle` is null -> stamp it with the ping timestamp
   - `retard_min = arrivee_reelle - arrivee_theorique`, rounded to minutes
   - propagate forward with margin absorption (below)
   - update `course.retard_min`
3. `MachineEtatCourse.evaluer(course)` — the ONLY place `course.statut` is
   assigned. Transitions are in the domain model doc.
4. `CalculateurEta.pour(course)` — `(pk_suivante - avancement) / vitesse_chainage`,
   floored at the theoretical arrival. **Read the unit warning below before
   writing this.**
5. Publish. `HubSse` fans out to the ligne channel and to the channels of the
   gares still ahead on this course.

### Units — the one that will silently corrupt every ETA

`avancement_km` and `pk_km` are **chainage**, measured against the line's
`distance_km`. The trace polyline is a different length — up to 40% longer on
some lines, per phase 2. The `vitesseKmh` carried on a ping is a **ground
speed**, because that is what AVL hardware reports.

Those are two different units. Dividing a chainage delta by a ground speed
gives an ETA wrong by a per-line factor of up to 1.4.

So `CalculateurEta` derives its own speed from chainage, never from the ping:

```
vitesse_chainage = (avancement[n] - avancement[n-k]) / (horodatage[n] - horodatage[n-k])
```

with k = 6 pings, falling back to the theoretical segment pace when fewer than
two pings exist. The reported `vitesseKmh` is passed through to the UI for
display and used nowhere in arithmetic.

This will not crash, and it will not look wrong. A train showing 14:44 instead
of 14:38 is entirely plausible, and the error only surfaces in phase 5 as
punctuality figures that are quietly a few points off. Write the unit into the
method name — `vitesseChainageKmh`, not `vitesse` — so a later session cannot
absent-mindedly substitute the ping value.

### Propagation with margin absorption

Walking the remaining stops in `ordre`, carrying the current delay:

```
retard = retard_courant
for passage in stops_ahead:
    retard = max(0, retard - passage.desserte.marge_min)
    passage.retard_min      = retard
    passage.arrivee_estimee = passage.arrivee_theorique + retard
    passage.depart_estimee  = passage.depart_theorique  + retard
```

Naive propagation — carrying the same delay unchanged to the terminus — is
wrong and an operations engineer will say so. Real timetables have padding;
`marge_min` is it. A train 11 minutes late at Sousse should arrive at Sfax
maybe 6 minutes late, not 11.

`arrivee_estimee` is never null and never recomputed by a client. Once
`arrivee_reelle` is stamped for a stop, its estimate is frozen and the UI shows
the real time instead.

### DetecteurSilence — two jobs, every 30s

1. **Feed died mid-run.** Any `EN_CIRCULATION` or `RETARDE` course whose
   `derniere_position_at` is older than 90 seconds moves to
   `ARRET_EXCEPTIONNEL` and emits a `statut` event. This is what makes the
   system degrade honestly instead of showing stale positions forever.

2. **Train late before it ever departs.** Any `A_QUAI` course whose
   `depart_theorique` passed more than 5 minutes ago with still no ping moves
   to `RETARDE`, with `retard_min = now - depart_theorique`, then runs the same
   propagation as above so every downstream estimate shifts.

Job 2 is easy to miss because nothing wakes the engine — there is no ping to
react to. Skip it and a 14:00 departure whose trainset never arrives displays
as on time on the platform board while the platform is empty. That is the most
visible failure a passenger can catch you in, and the one a jury will probe.

## Rules

- `EtatCirculationStore` is an interface. The impl is a `ConcurrentHashMap`.
  Decision 4 explains why there is no Redis; do not add one.
- SSE emitters go in a `Map<String, List<SseEmitter>>` keyed by channel, with
  `onCompletion` / `onTimeout` / `onError` all removing the emitter. Leaking
  emitters is the classic bug here — write the removal first.
- Emitter timeout 0 (never), heartbeat comment every 15s from a single scheduled
  task, not one per emitter.
- Deltas only. If you find yourself serialising a list of courses into an SSE
  event, stop — the client fetches the snapshot over REST.
- `/recherche?q=` matches train number, train name, ligne name, gare name and
  destination. One query with `ILIKE` and a union is fine; do not add a search
  engine.

## Acceptance

```bash
# with API + simulateur running at acceleration 20
timeout 30 curl -sN localhost:8080/api/v1/stream/lignes/1 | head -20
# expect: heartbeat comments and `event: position` frames with a data line

psql -h localhost -U trino -d trino -c \
  "select statut, count(*) from course where date_service = current_date group by statut"
# expect a mix: A_QUAI, EN_CIRCULATION, RETARDE, TERMINUS_ATTEINT

psql -h localhost -U trino -d trino -c \
  "select ordre, arrivee_theorique, arrivee_reelle, retard_min from passage_gare
   where course_id = (select id from course where retard_min > 5 limit 1) order by ordre"
# real times stamped in order; retard_min non-null and propagating forward

# estimates exist, are non-null, and shift with delay
psql -h localhost -U trino -d trino -c \
  "select ordre, arrivee_theorique, arrivee_estimee, retard_min from passage_gare
   where course_id = (select id from course where retard_min > 8 limit 1)
   and arrivee_reelle is null order by ordre"
# expect: arrivee_estimee > arrivee_theorique, and retard_min DECREASING with
# ordre wherever marge_min > 0 — that is margin absorption working

psql -h localhost -U trino -d trino -c \
  "select count(*) from passage_gare where arrivee_estimee is null"   # expect 0

# clients must not recompute expected times themselves
grep -rn "retardMin +\|arriveeTheorique +\|addMinutes" frontend/src --include=*.tsx
# expect no output — they read arriveeEstimee

# pre-departure delay: stop the simulator, let a departure slot pass, then
psql -h localhost -U trino -d trino -c \
  "select count(*) from course where statut = 'RETARDE' and derniere_position_at is null"
# expect > 0 once a depart_theorique is more than 5 min in the past

# state machine is the single writer
grep -rn "setStatut\|\.statut =" backend/api/src/main/java --include=*.java \
  | grep -v MachineEtatCourse | grep -v domaine/Course.java
# expect no output

# stop the simulator, wait 2 min, then:
psql -h localhost -U trino -d trino -c \
  "select count(*) from course where statut = 'ARRET_EXCEPTIONNEL'"   # > 0
```

## Then

Update `docs/STATE.md` and stop. Phases 0-3 are the system's spine; if the
schedule has slipped, this is the checkpoint to tell your supervisor.
