# Phase 2 — Planning + simulateur (3 days)

Also read: `docs/architecture/domain-model.md`, `docs/architecture/api-contract.md`
(Ingestion section), `docs/architecture/decisions.md` (records 2 and 5).

This is the architecturally important phase. Read decision 2 before starting.

## Goal

A daily timetable materialised into `course` + `passage_gare` rows, an
authenticated ingestion endpoint, and a separate simulator process that moves
trains along their line and POSTs GPS pings.

## Build — API side

```
backend/api/.../circulation/domaine/{Course,PassageGare,PositionCourse,StatutCourse,SensCourse,CauseRetard}.java
backend/api/.../circulation/repo/*.java
backend/api/.../circulation/service/GenerateurCourses.java    desserte -> courses
backend/api/.../circulation/service/IngestionService.java
backend/api/.../circulation/web/IngestionController.java
backend/api/.../circulation/geo/GeometrieLigne.java           haversine + interpolation
backend/api/.../securite/FiltreCleIngestion.java              X-Ingest-Key
backend/api/src/main/resources/db/migration/V4__circulation.sql
```

### V4 also alters `desserte`

`marge_min` belongs on `desserte`, but V1 is already applied and migrations are
immutable. So V4 does both jobs:

```sql
alter table desserte add column marge_min smallint not null default 0;
update desserte set marge_min = 2 where ligne_id in (1, 3);  -- grandes lignes
update desserte set marge_min = 1 where ligne_id = 2;
-- banlieue and metro keep 0: no padding in a suburban timetable
```

`marge_min` is the schedule slack on the segment arriving at that stop. It is
what lets a late train make time back instead of carrying the same delay to the
terminus. See `docs/architecture/domain-model.md`.

`GenerateurCourses` runs at startup and on a daily `@Scheduled` at 03:00. For
each ligne and each departure slot in a hardcoded `horaires` table (add it to
V4), it creates a `Course` and one `PassageGare` per desserte entry, with
theoretical times = departure + offset, and
`arrivee_estimee = arrivee_theorique`, `depart_estimee = depart_theorique`.
Estimates start equal to the plan and are revised by the engine in phase 3 —
they are never null. Idempotent: unique constraint on
(train, date, départ) and `ON CONFLICT DO NOTHING` semantics.

`GeometrieLigne` needs exactly two operations:
- `positionA(traceKm)` -> lat/lon by walking the polyline
- `longueurTotale()` -> km

## Build — simulateur

```
backend/simulateur/pom.xml                        web client only, no JPA
backend/simulateur/.../SimulateurApplication.java
backend/simulateur/.../ClientTrino.java           GET courses-du-jour, POST positions
backend/simulateur/.../MoteurSimulation.java      the tick
backend/simulateur/.../ProfilPerturbation.java    injects delays
backend/simulateur/src/main/resources/application.yml
```

The simulator holds all state in memory. It never touches the database — this
is invariant 3 and the whole point of the phase.

Tick every 5 seconds:
1. Any course whose `departTheorique` has passed and is not yet started becomes
   active at km 0.
2. Advance each active course by `vitesse * 5s`, where vitesse is drawn around
   the line's `vitesse_max_kmh` with noise.
3. `ProfilPerturbation` occasionally applies a slowdown or a station overstay,
   with a weighted random `CauseRetard`. Target roughly 25% of courses ending
   the day delayed — enough that the dashboard is not empty, not so much that
   punctuality looks broken.
4. POST the batch.

Configurable: `trino.simulateur.acceleration` (default 1.0). At 60.0 a full
service day runs in 24 minutes — you will want this for the demo and for
generating report data.

## Rules

- The simulator authenticates with `X-Ingest-Key` and nothing else. It has no
  JWT, no user, no session.
- `IngestionService` writes `position_course` and updates
  `course.avancement_km` / `derniere_position_at`. It does NOT compute delays or
  change status — that is phase 3. Keep the seam clean.
- Batch inserts. One `saveAll` per request, not one insert per ping.

## Prerequisite — seed consistency

The delay engine in phase 3 walks stops assuming `ordre` and `pk_km` ascend
together. Violations do not crash; they produce plausible-looking, wrong
punctuality numbers. Fix the seed before writing any code this phase.

Real SNCFT branch topology is out of scope — only internal consistency matters.
Do not spend a day researching which branch Mateur is really on.

All three queries must return zero rows:

```sql
-- ordre and pk_km must agree, per ligne
select d.ligne_id, d.ordre, d.pk_km
from desserte d
where exists (
  select 1 from desserte d2
  where d2.ligne_id = d.ligne_id and d2.ordre < d.ordre and d2.pk_km >= d.pk_km
);

-- no two stops on a line at the same point (zero-length segment breaks the ETA divisor)
select ligne_id
from desserte d join gare g on g.id = d.gare_id
group by ligne_id
having count(*) <> count(distinct (g.latitude || ',' || g.longitude));

-- pk_km must fit inside the line
select d.ligne_id, max(d.pk_km), l.distance_km
from desserte d join ligne l on l.id = d.ligne_id
group by d.ligne_id, l.distance_km having max(d.pk_km) > l.distance_km;

-- a train may not be faster than its line
select t.numero, t.vitesse_max_kmh, l.vitesse_max_kmh
from train t join ligne l on l.id = t.ligne_id
where t.vitesse_max_kmh > l.vitesse_max_kmh;
```

Fix by editing `V2__seed_reseau.sql` and running `docker compose down -v`. V2 is
unreleased seed data on a rebuildable local database — a corrective migration
would only clutter the history. `V1` stays untouched. Note the exception in
`docs/STATE.md` so a later session does not treat it as a violated invariant.

Then port these four queries into `GeometrieLigneTest` as assertions, so the
build fails if the seed regresses.

## Acceptance

```bash
curl -s -H "X-Ingest-Key: dev-key" localhost:8080/api/v1/ingest/courses-du-jour \
  | jq '.[0] | {courseId, ligne: .ligne.nom, nbDessertes: (.desserte|length), tracePoints: (.trace|length)}'
# every field populated, nbDessertes >= 2, tracePoints >= 20

cd backend && ./mvnw -q -pl simulateur spring-boot:run &
sleep 40
psql -h localhost -U trino -d trino -c "select count(*) from position_course"   # > 0
psql -h localhost -U trino -d trino -c \
  "select c.id, c.avancement_km from course c where c.avancement_km > 0 limit 5"

# invariant check — the simulator must not own a datasource
grep -r "spring-boot-starter-data-jpa\|DataSource" backend/simulateur/ && echo FAIL || echo OK

curl -s -o /dev/null -w '%{http_code}' -X POST localhost:8080/api/v1/ingest/positions \
  -H 'Content-Type: application/json' -d '{"pings":[]}'    # expect 401 without key
```

## Then

Update `docs/STATE.md` and stop.
