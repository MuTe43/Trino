-- Phase 6: exploitation.incident -- what an agent declares when something goes
-- wrong on the network, and what the responsable resolves.
--
-- V6 is already applied (backfill of passage_gare.quai), so this is V7 rather
-- than the V6 the phase file first named: applied migrations are immutable.

create table incident (
    id          bigserial primary key,
    type        varchar(30) not null,
    description text        not null,
    survenu_at  timestamptz not null,
    gare_id     bigint      null,
    ligne_id    bigint      null,
    course_id   bigint      null,
    gravite     varchar(10) not null,
    impact      text        not null,
    statut      varchar(10) not null default 'OUVERT',
    declare_par bigint      not null,
    resolu_at   timestamptz null,
    constraint fk_incident_gare foreign key (gare_id) references gare (id),
    constraint fk_incident_ligne foreign key (ligne_id) references ligne (id),
    constraint fk_incident_course foreign key (course_id) references course (id),
    constraint fk_incident_declarant foreign key (declare_par) references utilisateur (id),
    constraint chk_incident_type check (type in (
        'PANNE_LOCOMOTIVE', 'DEFAUT_SIGNALISATION', 'ACCIDENT', 'OBSTACLE_VOIE',
        'INTEMPERIES', 'COUPURE_ELECTRIQUE', 'TRAVAUX', 'AUTRE')),
    constraint chk_incident_gravite check (gravite in ('MINEURE', 'MOYENNE', 'MAJEURE', 'CRITIQUE')),
    constraint chk_incident_statut check (statut in ('OUVERT', 'EN_COURS', 'RESOLU')),
    -- A biconditional, not two separate rules: a RESOLU row without a
    -- resolution time makes `resoluAt` unusable for reporting, and a resolution
    -- time on a row that is still OUVERT is worse -- the incidents report would
    -- count it as closed while the console shows it open.
    constraint chk_incident_resolu_at check ((statut = 'RESOLU') = (resolu_at is not null)),
    -- At least one location. An incident attached to nothing publishes on no
    -- SSE channel and draws no marker, so it would be invisible everywhere but
    -- the list -- a silent black hole rather than a network-wide declaration.
    -- domain-model.md leaves all three nullable individually; this rule only
    -- forbids all three being null at once.
    constraint chk_incident_localisation check (
        gare_id is not null or ligne_id is not null or course_id is not null)
);

-- The console's default view is "what is open right now".
create index idx_incident_statut on incident (statut);

-- The supervision map and the passenger map both read by ligne, newest first;
-- the incidents report reads by window over the same column.
create index idx_incident_ligne_survenu on incident (ligne_id, survenu_at desc);
create index idx_incident_survenu on incident (survenu_at desc);
