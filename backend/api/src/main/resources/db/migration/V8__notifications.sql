-- Phase 8: notifications et alertes -- who asked to hear about what, what the
-- administrator configured, and what was actually emitted.
--
-- Also carries the two journal_connexion indexes phase 7 could not create: that
-- phase was forbidden a migration, and GET /journal-connexions filters and sorts
-- on exactly these columns over a table that has been gathering a row per login
-- attempt since phase 1.

create index idx_journal_horodatage on journal_connexion (horodatage desc);
create index idx_journal_utilisateur on journal_connexion (utilisateur_id, horodatage desc);

-- ---------------------------------------------------------------------------
-- abonnement -- who wants to hear about what.
-- ---------------------------------------------------------------------------

create table abonnement (
    id             bigserial   primary key,
    utilisateur_id bigint      null,
    jeton_anonyme  varchar(64) null,
    cible_type     varchar(10) not null,
    cible_id       bigint      not null,
    canaux         varchar(80) not null,
    -- Not in the phase file's column list, but POST /abonnements accepts
    -- {..., email?} and CanalEmail has to know where to send at event time,
    -- long after the request that created the row is gone. Without it the
    -- EMAIL channel could be subscribed to and never delivered. Null for an
    -- account subscription, which falls back to utilisateur.email.
    email          varchar(160) null,
    cree_at        timestamptz not null,
    constraint fk_abonnement_utilisateur foreign key (utilisateur_id) references utilisateur (id),
    constraint chk_abonnement_cible_type check (cible_type in ('COURSE', 'LIGNE', 'GARE')),
    -- Exactly one identity, not "at least one". A row with neither belongs to
    -- nobody: unreadable, undeletable, and still generating notifications. A row
    -- with both is worse -- a logged-in browser still carries the anonymous
    -- cookie, so it would satisfy both partial uniques below and notify the same
    -- person twice for one event.
    constraint chk_abonnement_identite check (num_nonnulls(utilisateur_id, jeton_anonyme) = 1)
);

-- Partial, not plain unique constraints: Postgres treats nulls as distinct, so a
-- single unique over (jeton_anonyme, cible_type, cible_id) constrains the
-- anonymous half only and lets an account subscriber duplicate without limit.
create unique index uq_abonnement_anonyme
    on abonnement (jeton_anonyme, cible_type, cible_id)
    where jeton_anonyme is not null;

create unique index uq_abonnement_utilisateur
    on abonnement (utilisateur_id, cible_type, cible_id)
    where utilisateur_id is not null;

-- The engine resolves subscribers by target on every matched event.
create index idx_abonnement_cible on abonnement (cible_type, cible_id);

-- ---------------------------------------------------------------------------
-- regle_alerte -- what the administrator configures. This is "gérer les alertes".
-- ---------------------------------------------------------------------------

create table regle_alerte (
    id          bigserial   primary key,
    evenement   varchar(20) not null,
    seuil_min   smallint    null,
    gravite_min varchar(10) null,
    canaux      varchar(80) not null,
    actif       boolean     not null default true,
    -- Null until a human edits the row: the four seeded defaults below were
    -- configured by nobody, and stamping them with utilisateur 1 would put a
    -- name against a decision that person never made.
    modifie_par bigint      null,
    constraint fk_regle_modifiee_par foreign key (modifie_par) references utilisateur (id),
    constraint chk_regle_evenement check (evenement in (
        'RETARD_SEUIL', 'COURSE_ANNULEE', 'INCIDENT_DECLARE', 'INCIDENT_RESOLU')),
    constraint chk_regle_gravite_min check (
        gravite_min is null or gravite_min in ('MINEURE', 'MOYENNE', 'MAJEURE', 'CRITIQUE')),
    -- A delay rule with no threshold would fire on every revision of every
    -- estimate, which is the flood the deduplication window exists to survive
    -- rather than to create.
    constraint chk_regle_seuil check (
        (evenement <> 'RETARD_SEUIL' and seuil_min is null)
        or (evenement = 'RETARD_SEUIL' and seuil_min is not null and seuil_min > 0))
);

-- The engine reads "the active rules for this event" on every domain event.
create index idx_regle_evenement on regle_alerte (evenement, actif);

-- Four defaults, so a fresh database notifies without an administrator having to
-- open the console first. The acceptance walkthrough subscribes and expects a
-- notification before it ever creates a rule; with an empty table it would get
-- none and the failure would look like a broken engine.
--
-- Channels are all four here on purpose: the rule says which channels an event
-- is ALLOWED to use, the subscription says which ones a passenger WANTS, and the
-- engine emits on the intersection. Seeding all four leaves the choice entirely
-- with the subscriber, which is the behaviour to start from.
insert into regle_alerte (evenement, seuil_min, gravite_min, canaux, actif) values
    ('RETARD_SEUIL',     5,    null,      'IN_APP,EMAIL,SMS,AFFICHAGE', true),
    ('COURSE_ANNULEE',   null, null,      'IN_APP,EMAIL,SMS,AFFICHAGE', true),
    ('INCIDENT_DECLARE', null, 'MOYENNE', 'IN_APP,EMAIL,SMS,AFFICHAGE', true),
    ('INCIDENT_RESOLU',  null, null,      'IN_APP,EMAIL,SMS,AFFICHAGE', true);

-- ---------------------------------------------------------------------------
-- notification -- what was actually emitted.
-- ---------------------------------------------------------------------------

create table notification (
    id            bigserial    primary key,
    abonnement_id bigint       null,
    -- Which rule fired, and on which course. Neither is in the phase file's
    -- column list, but the deduplication key is (abonnement, evenement, course)
    -- and the acceptance query groups on `evenement` -- without the columns
    -- there is no way to check the guard from outside the process.
    evenement     varchar(20)  not null,
    course_id     bigint       null,
    destinataire  varchar(160) not null,
    canal         varchar(10)  not null,
    sujet         varchar(200) not null,
    contenu       text         not null,
    statut        varchar(10)  not null default 'EN_ATTENTE',
    -- Null while EN_ATTENTE: stamped when the dispatcher finishes, on success
    -- and on failure alike, so it always reads as "when the attempt completed".
    envoye_at     timestamptz  null,
    erreur        text         null,
    constraint fk_notification_abonnement foreign key (abonnement_id) references abonnement (id),
    constraint fk_notification_course foreign key (course_id) references course (id),
    constraint chk_notification_canal check (canal in ('IN_APP', 'EMAIL', 'SMS', 'AFFICHAGE')),
    constraint chk_notification_statut check (statut in ('EN_ATTENTE', 'ENVOYE', 'ECHEC')),
    constraint chk_notification_evenement check (evenement in (
        'RETARD_SEUIL', 'COURSE_ANNULEE', 'INCIDENT_DECLARE', 'INCIDENT_RESOLU'))
);

create index idx_notification_abonnement on notification (abonnement_id, envoye_at desc);
