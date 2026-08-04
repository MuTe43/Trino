# Domain model

## The one thing to get right

The cahier des charges puts `Statut` on the train (section 4.2). That is wrong,
and the spec's own delay formula (4.6) proves it: a status like *retardé* or
*terminus atteint* belongs to a journey on a date, not to a locomotive. The same
trainset runs Tunis->Sousse in the morning and the return at night. Two runs,
two statuses, one train.

So the model splits:

- `Train` — rolling stock. Number, name, type, capacity, max speed. No status.
- `Course` — one dated run. Holds status, current delay, delay cause.

The spec also never defines where *heure théorique* comes from. `Desserte`
supplies it: the ordered stop pattern of a line, with an offset in minutes from
departure for each station. A `Course` is materialised from a `Desserte` plus a
departure time, producing one `PassageGare` row per stop.

Flag both of these to the supervisor in writing.

## Tables

### referentiel

**gare**
`id` bigserial PK · `code` varchar(10) unique · `nom` varchar(120) ·
`region` varchar(80) · `latitude` numeric(9,6) · `longitude` numeric(9,6) ·
`nb_quais` smallint · `responsable` varchar(120) · `actif` boolean

**ligne**
`id` bigserial PK · `code` varchar(20) unique · `nom` varchar(160) ·
`distance_km` numeric(7,2) · `vitesse_max_kmh` smallint ·
`temps_theorique_min` smallint · `trace` jsonb (ordered `[[lon,lat],...]`) ·
`actif` boolean

`trace` is the polyline the simulator interpolates along. 20-60 points per line
is plenty. No PostGIS.

**desserte** — the theoretical stop pattern. This is the missing entity.
`id` bigserial PK · `ligne_id` FK · `gare_id` FK · `ordre` smallint ·
`pk_km` numeric(7,2) (distance from line origin) ·
`offset_arrivee_min` smallint · `offset_depart_min` smallint ·
`marge_min` smallint default 0
unique (`ligne_id`,`ordre`), unique (`ligne_id`,`gare_id`)

`marge_min` is the schedule padding built into the segment *arriving at* this
stop — the slack a real timetable leaves so a late train can make time back.
Seed 0 on suburban stops and 1-3 on long-distance segments.

**train**
`id` bigserial PK · `numero` varchar(20) unique · `nom` varchar(120) ·
`type` enum TypeTrain · `ligne_id` FK · `capacite` smallint ·
`vitesse_max_kmh` smallint · `actif` boolean

### circulation

**course**
`id` bigserial PK · `train_id` FK · `ligne_id` FK · `date_service` date ·
`sens` enum SensCourse · `depart_theorique` timestamptz ·
`arrivee_theorique` timestamptz · `statut` enum StatutCourse ·
`retard_min` int default 0 · `cause_retard` enum CauseRetard null ·
`avancement_km` numeric(7,2) default 0 · `derniere_position_at` timestamptz null
unique (`train_id`,`date_service`,`depart_theorique`)
index on (`date_service`,`statut`), (`ligne_id`,`date_service`)

**passage_gare**
`id` bigserial PK · `course_id` FK · `gare_id` FK · `ordre` smallint ·
`arrivee_theorique` timestamptz · `depart_theorique` timestamptz ·
`arrivee_reelle` timestamptz null · `depart_reelle` timestamptz null ·
`arrivee_estimee` timestamptz · `depart_estimee` timestamptz ·
`quai` varchar(10) null · `retard_min` int default 0
unique (`course_id`,`ordre`)

### The three times — spec section 4.5

The cahier des charges asks for *heure prévue*, *heure estimée* and *heure
réelle* as three distinct display fields. They map to three column pairs and
they are never conflated:

| Field | Column | Meaning | Changes? |
|---|---|---|---|
| Heure prévue | `arrivee_theorique` | The published timetable. The contract. | Never |
| Heure estimée | `arrivee_estimee` | Current best prediction for a stop not yet reached. | On every ping |
| Heure réelle | `arrivee_reelle` | Observed. Null until the train passes. | Once, on arrival |

At creation, each estimate equals its theoretical counterpart. The engine
revises it forward as delay accrues.

**An estimate is null exactly when its theoretical counterpart is null.** An
origin has no `arrivee_theorique`, so it has no `arrivee_estimee`; a terminus
has no departure. Any check asserting `arrivee_estimee is not null` across all
rows is unsatisfiable by design — qualify it with
`where arrivee_theorique is not null`.

`arrivee_estimee` freezes once `arrivee_reelle` is stamped. `depart_estimee`
does **not** — it must keep tracking until `depart_reelle` exists, or a train
held at a platform slips past its own stale estimate and disappears from the
station board.

Clients never compute an expected time by adding `retard_min` to a theoretical
time. They read `arrivee_estimee`. This is the single source of truth for
"when will it actually get here", and it keeps the map panel, the station page
and the kiosk board from drifting apart.

**position_course** — history, append-only, one row per ingested ping.
`id` bigserial PK · `course_id` FK · `horodatage` timestamptz ·
`latitude` numeric(9,6) · `longitude` numeric(9,6) · `vitesse_kmh` smallint ·
`avancement_km` numeric(7,2) · `gare_precedente_id` FK null ·
`gare_suivante_id` FK null · `eta_suivante` timestamptz null
index on (`course_id`,`horodatage` desc)

Latest position per course lives in memory (`EtatCirculationStore`). This table
is for history and reports only — never query it on the hot path.

### exploitation

**incident**
`id` bigserial PK · `type` enum TypeIncident · `description` text ·
`survenu_at` timestamptz · `gare_id` FK null · `ligne_id` FK null ·
`course_id` FK null · `gravite` enum Gravite · `impact` text ·
`statut` enum StatutIncident · `declare_par` FK utilisateur ·
`resolu_at` timestamptz null

### iam

**utilisateur**
`id` bigserial PK · `email` varchar(160) unique · `mot_de_passe_hash` varchar(72) ·
`nom` varchar(120) · `role` enum Role · `actif` boolean · `cree_at` timestamptz

**journal_connexion**
`id` bigserial PK · `utilisateur_id` FK null · `email_tente` varchar(160) ·
`adresse_ip` varchar(45) · `user_agent` text · `succes` boolean ·
`horodatage` timestamptz

## Enums

```
TypeTrain        EXPRESS, BANLIEUE, GRANDES_LIGNES, FRET
SensCourse       ALLER, RETOUR
StatutCourse     A_QUAI, EN_CIRCULATION, RETARDE, ARRET_EXCEPTIONNEL,
                 ANNULE, TERMINUS_ATTEINT
CauseRetard      INCIDENT_TECHNIQUE, METEO, ACCIDENT, SIGNALISATION, TRAVAUX,
                 ATTENTE_CORRESPONDANCE, AFFLUENCE_VOYAGEURS, AUTRE
TypeIncident     PANNE_LOCOMOTIVE, DEFAUT_SIGNALISATION, ACCIDENT,
                 OBSTACLE_VOIE, INTEMPERIES, COUPURE_ELECTRIQUE, TRAVAUX, AUTRE
Gravite          MINEURE, MOYENNE, MAJEURE, CRITIQUE
StatutIncident   OUVERT, EN_COURS, RESOLU
Role             VOYAGEUR, AGENT_CIRCULATION, RESPONSABLE_EXPLOITATION, ADMINISTRATEUR
```

## Delay classification

Derived, never stored. `retard_min` -> bucket:
`<5` A_L_HEURE · `5-9` R5 · `10-14` R10 · `15-29` R15 · `30-59` R30 ·
`>=60` R60_PLUS

## Course state machine

```
A_QUAI --(first position ping)--> EN_CIRCULATION
A_QUAI --(now > depart_theorique + 5 min, still no ping)--> RETARDE
EN_CIRCULATION --(retard_min >= 5)--> RETARDE
RETARDE --(retard_min < 5)--> EN_CIRCULATION
EN_CIRCULATION|RETARDE --(no ping for > 90s)--> ARRET_EXCEPTIONNEL
ARRET_EXCEPTIONNEL --(ping resumes)--> previous state
any --(agent action)--> ANNULE
EN_CIRCULATION|RETARDE --(last passage_gare has arrivee_reelle)--> TERMINUS_ATTEINT
```

Transitions are computed in one place: `MachineEtatCourse`. Nowhere else may
set `course.statut`.

The second transition is the origin-station case and it is easy to miss: a
course whose trainset never shows up receives no ping, so nothing wakes the
engine. Without it, a 14:00 departure displays as on time on the platform
board while the platform is empty — the single most visible failure a passenger
can catch you in. `DetecteurSilence` owns this check.

## Seed data

Real SNCFT network, enough to look credible in a demo:
- Tunis - Sousse - Sfax - Gabès (grandes lignes)
- Tunis - Bizerte
- Tunis - Kalaâ Khasba
- Banlieue Sud: Tunis - Borj Cedria
- Métro du Sahel: Sousse - Monastir - Mahdia

Target roughly 40 gares, 5 lignes, 25 trains, and a timetable producing 60-90
courses per day. Coordinates from OpenStreetMap. Put the seed in a Flyway
migration so it is reproducible, not in a script someone forgets to run.
