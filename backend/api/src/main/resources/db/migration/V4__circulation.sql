-- Phase 2: circulation schema (course, passage_gare, position_course) plus the
-- hardcoded departure-slot table the daily timetable is materialised from.
-- Also back-fills `marge_min` onto desserte: it belongs there, but V1 is
-- already applied and applied migrations are immutable, so V4 does both jobs.

-- ---------------------------------------------------------------------
-- desserte.marge_min -- schedule slack on the segment ARRIVING at this stop
-- ---------------------------------------------------------------------
alter table desserte add column marge_min smallint not null default 0;

-- Keyed on ligne.code rather than a literal id: the ids happen to be 1..5
-- today, but nothing guarantees bigserial ordering if the seed is ever
-- reordered, and a silently mis-targeted update here would only surface as
-- wrong punctuality numbers much later.
update desserte set marge_min = 2
 where ligne_id in (select id from ligne where code in ('L1-TSFG', 'L3-TKK'));
update desserte set marge_min = 1
 where ligne_id in (select id from ligne where code = 'L2-TBZ');
-- banlieue (L4) and métro (L5) keep 0: no padding in a suburban timetable.

-- The first stop of a ligne has no arriving segment, so it can carry no slack.
update desserte set marge_min = 0 where ordre = 1;

-- ---------------------------------------------------------------------
-- horaire -- the departure slots the daily timetable is generated from
-- ---------------------------------------------------------------------
create table horaire (
    id           bigserial primary key,
    ligne_id     bigint      not null,
    train_id     bigint      not null,
    sens         varchar(10) not null,
    heure_depart time        not null,
    actif        boolean     not null default true,
    constraint fk_horaire_ligne foreign key (ligne_id) references ligne(id),
    constraint fk_horaire_train foreign key (train_id) references train(id),
    constraint uq_horaire_train_heure unique (train_id, heure_depart),
    constraint chk_horaire_sens check (sens in ('ALLER', 'RETOUR'))
);

create index idx_horaire_ligne on horaire (ligne_id);

-- Slots are spaced so a trainset is never booked onto a second run before the
-- first one could plausibly have finished (temps_theorique_min per ligne).
insert into horaire (ligne_id, train_id, sens, heure_depart)
select t.ligne_id, t.id, v.sens, v.heure_depart::time
from train t, (values
    -- L1-TSFG, 330 min end to end
    ('TN101', 'ALLER',  '05:30'), ('TN101', 'RETOUR', '12:00'),
    ('TN102', 'ALLER',  '07:00'), ('TN102', 'RETOUR', '14:00'),
    ('TN103', 'ALLER',  '08:30'), ('TN103', 'RETOUR', '15:30'),
    ('TN104', 'ALLER',  '10:00'), ('TN104', 'RETOUR', '17:00'),
    ('TN105', 'ALLER',  '12:30'), ('TN105', 'RETOUR', '19:30'),
    ('TN106', 'ALLER',  '14:00'), ('TN106', 'RETOUR', '21:00'),
    ('TN107', 'ALLER',  '16:30'),
    ('FR101', 'ALLER',  '22:00'),
    -- L2-TBZ, 105 min
    ('TN201', 'ALLER',  '06:00'), ('TN201', 'RETOUR', '08:30'),
    ('TN201', 'ALLER',  '11:00'), ('TN201', 'RETOUR', '13:30'),
    ('TN202', 'ALLER',  '07:15'), ('TN202', 'RETOUR', '09:45'),
    ('TN202', 'ALLER',  '15:00'), ('TN202', 'RETOUR', '17:30'),
    ('TN203', 'ALLER',  '12:15'), ('TN203', 'RETOUR', '14:45'),
    ('TN203', 'ALLER',  '18:00'), ('TN203', 'RETOUR', '20:15'),
    ('FR201', 'ALLER',  '21:30'),
    -- L3-TKK, 240 min
    ('TN301', 'ALLER',  '06:15'), ('TN301', 'RETOUR', '11:00'),
    ('TN302', 'ALLER',  '08:00'), ('TN302', 'RETOUR', '13:00'),
    ('TN303', 'ALLER',  '13:45'), ('TN303', 'RETOUR', '18:30'),
    ('FR301', 'ALLER',  '20:00'),
    -- L4-BS, 35 min, suburban frequency
    ('BS401', 'ALLER',  '05:45'), ('BS401', 'RETOUR', '06:45'),
    ('BS401', 'ALLER',  '07:45'), ('BS401', 'RETOUR', '08:45'),
    ('BS401', 'ALLER',  '16:00'), ('BS401', 'RETOUR', '17:00'),
    ('BS402', 'ALLER',  '06:00'), ('BS402', 'RETOUR', '07:00'),
    ('BS402', 'ALLER',  '08:00'), ('BS402', 'RETOUR', '09:00'),
    ('BS402', 'ALLER',  '16:30'), ('BS402', 'RETOUR', '17:30'),
    ('BS403', 'ALLER',  '06:15'), ('BS403', 'RETOUR', '07:15'),
    ('BS403', 'ALLER',  '12:00'), ('BS403', 'RETOUR', '13:00'),
    ('BS403', 'ALLER',  '18:00'), ('BS403', 'RETOUR', '19:00'),
    ('BS404', 'ALLER',  '06:30'), ('BS404', 'RETOUR', '07:30'),
    ('BS404', 'ALLER',  '14:00'), ('BS404', 'RETOUR', '15:00'),
    ('BS404', 'ALLER',  '18:30'), ('BS404', 'RETOUR', '19:30'),
    ('BS405', 'ALLER',  '09:30'), ('BS405', 'RETOUR', '10:30'),
    ('BS405', 'ALLER',  '20:00'), ('BS405', 'RETOUR', '21:00'),
    -- L5-MSA, 75 min
    ('MS501', 'ALLER',  '06:00'), ('MS501', 'RETOUR', '07:30'),
    ('MS501', 'ALLER',  '15:00'), ('MS501', 'RETOUR', '16:30'),
    ('MS502', 'ALLER',  '07:00'), ('MS502', 'RETOUR', '08:30'),
    ('MS502', 'ALLER',  '16:00'), ('MS502', 'RETOUR', '17:30'),
    ('MS503', 'ALLER',  '09:00'), ('MS503', 'RETOUR', '10:30'),
    ('MS503', 'ALLER',  '18:00'), ('MS503', 'RETOUR', '19:30'),
    ('MS504', 'ALLER',  '11:00'), ('MS504', 'RETOUR', '12:30'),
    ('MS504', 'ALLER',  '19:00'), ('MS504', 'RETOUR', '20:30'),
    ('MS505', 'ALLER',  '13:00'), ('MS505', 'RETOUR', '14:30')
) as v(numero, sens, heure_depart)
where t.numero = v.numero;

-- ---------------------------------------------------------------------
-- course -- one dated run of a train on a ligne
-- ---------------------------------------------------------------------
create table course (
    id                   bigserial primary key,
    train_id             bigint       not null,
    ligne_id             bigint       not null,
    date_service         date         not null,
    sens                 varchar(10)  not null,
    depart_theorique     timestamptz  not null,
    arrivee_theorique    timestamptz  not null,
    statut               varchar(24)  not null default 'A_QUAI',
    retard_min           int          not null default 0,
    cause_retard         varchar(30),
    avancement_km        numeric(7,2) not null default 0,
    derniere_position_at timestamptz,
    constraint fk_course_train foreign key (train_id) references train(id),
    constraint fk_course_ligne foreign key (ligne_id) references ligne(id),
    -- idempotency key for GenerateurCourses: re-running a day is a no-op
    constraint uq_course_train_date_depart unique (train_id, date_service, depart_theorique),
    constraint chk_course_sens check (sens in ('ALLER', 'RETOUR')),
    constraint chk_course_statut check (statut in (
        'A_QUAI', 'EN_CIRCULATION', 'RETARDE', 'ARRET_EXCEPTIONNEL',
        'ANNULE', 'TERMINUS_ATTEINT')),
    constraint chk_course_cause check (cause_retard is null or cause_retard in (
        'INCIDENT_TECHNIQUE', 'METEO', 'ACCIDENT', 'SIGNALISATION', 'TRAVAUX',
        'ATTENTE_CORRESPONDANCE', 'AFFLUENCE_VOYAGEURS', 'AUTRE'))
);

create index idx_course_date_statut on course (date_service, statut);
create index idx_course_ligne_date  on course (ligne_id, date_service);

-- ---------------------------------------------------------------------
-- passage_gare -- one row per stop of a course
-- ---------------------------------------------------------------------
-- `arrivee_*` is null at the origin and `depart_*` is null at the terminus:
-- a train does not arrive at where it starts. Everywhere a theoretical time
-- exists, the matching estimate exists too and starts equal to it (phase 3
-- revises it), which is what the "estimates are never null" rule means.
--
-- pk_km and marge_min are copied here rather than joined from desserte on
-- read: a RETOUR course walks the desserte mirrored (pk' = distance - pk,
-- ordre reversed), so the values differ per sens. Recomputing that mirror on
-- every position ping -- the hot path for phase 3's ETA -- would mean a join
-- plus a branch in the one place that has to stay simple.
create table passage_gare (
    id                bigserial primary key,
    course_id         bigint       not null,
    gare_id           bigint       not null,
    ordre             smallint     not null,
    pk_km             numeric(7,2) not null,
    marge_min         smallint     not null default 0,
    arrivee_theorique timestamptz,
    depart_theorique  timestamptz,
    arrivee_estimee   timestamptz,
    depart_estimee    timestamptz,
    arrivee_reelle    timestamptz,
    depart_reelle     timestamptz,
    quai              varchar(10),
    retard_min        int          not null default 0,
    constraint fk_passage_course foreign key (course_id) references course(id) on delete cascade,
    constraint fk_passage_gare   foreign key (gare_id)   references gare(id),
    constraint uq_passage_course_ordre unique (course_id, ordre),
    constraint chk_passage_estimee_suit_theorique check (
        (arrivee_theorique is null) = (arrivee_estimee is null) and
        (depart_theorique  is null) = (depart_estimee  is null))
);

create index idx_passage_gare_id on passage_gare (gare_id);

-- ---------------------------------------------------------------------
-- position_course -- append-only ping history, never read on the hot path
-- ---------------------------------------------------------------------
create table position_course (
    id                 bigserial primary key,
    course_id          bigint       not null,
    horodatage         timestamptz  not null,
    latitude           numeric(9,6) not null,
    longitude          numeric(9,6) not null,
    vitesse_kmh        smallint,
    avancement_km      numeric(7,2),
    gare_precedente_id bigint,
    gare_suivante_id   bigint,
    eta_suivante       timestamptz,
    constraint fk_position_course     foreign key (course_id)          references course(id) on delete cascade,
    constraint fk_position_precedente foreign key (gare_precedente_id) references gare(id),
    constraint fk_position_suivante   foreign key (gare_suivante_id)   references gare(id)
);

create index idx_position_course_horodatage on position_course (course_id, horodatage desc);
