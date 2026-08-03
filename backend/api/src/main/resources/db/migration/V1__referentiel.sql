-- Phase 0: référentiel schema (gare, ligne, desserte, train).
-- Circulation, exploitation and iam tables are out of scope for this phase.

create table gare (
    id         bigserial primary key,
    code       varchar(10)   not null unique,
    nom        varchar(120)  not null,
    region     varchar(80),
    latitude   numeric(9,6)  not null,
    longitude  numeric(9,6)  not null,
    nb_quais   smallint,
    responsable varchar(120),
    actif      boolean       not null default true
);

create table ligne (
    id                    bigserial primary key,
    code                  varchar(20)  not null unique,
    nom                   varchar(160) not null,
    distance_km           numeric(7,2),
    vitesse_max_kmh       smallint,
    temps_theorique_min   smallint,
    trace                 jsonb, -- ordered [[lon,lat],...] polyline, 20-60 points
    actif                 boolean not null default true
);

create table desserte (
    id                    bigserial primary key,
    ligne_id              bigint   not null,
    gare_id               bigint   not null,
    ordre                 smallint not null,
    pk_km                 numeric(7,2),
    offset_arrivee_min    smallint,
    offset_depart_min     smallint,
    constraint fk_desserte_ligne_id foreign key (ligne_id) references ligne(id),
    constraint fk_desserte_gare_id  foreign key (gare_id)  references gare(id),
    constraint uq_desserte_ligne_ordre unique (ligne_id, ordre),
    constraint uq_desserte_ligne_gare  unique (ligne_id, gare_id)
);

-- Supports "ordered stop pattern of a ligne" query (GET /lignes/{id}/desserte).
create index idx_desserte_gare_id on desserte (gare_id);

create table train (
    id               bigserial primary key,
    numero           varchar(20)  not null unique,
    nom              varchar(120),
    type             varchar(20)  not null,
    ligne_id         bigint,
    capacite         smallint,
    vitesse_max_kmh  smallint,
    actif            boolean      not null default true,
    constraint fk_train_ligne_id foreign key (ligne_id) references ligne(id),
    constraint chk_train_type check (type in ('EXPRESS', 'BANLIEUE', 'GRANDES_LIGNES', 'FRET'))
);

-- Supports "list trains of a ligne" query.
create index idx_train_ligne_id on train (ligne_id);
