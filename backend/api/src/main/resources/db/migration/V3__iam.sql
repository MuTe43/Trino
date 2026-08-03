-- Phase 1: auth & rôles (utilisateur, journal_connexion, refresh_token) + seed users.

create table utilisateur (
    id                 bigserial primary key,
    email              varchar(160) not null unique,
    mot_de_passe_hash  varchar(72)  not null,
    nom                varchar(120) not null,
    role               varchar(30)  not null,
    actif              boolean      not null default true,
    cree_at            timestamptz  not null default now(),
    constraint chk_utilisateur_role check (role in ('VOYAGEUR', 'AGENT_CIRCULATION', 'RESPONSABLE_EXPLOITATION', 'ADMINISTRATEUR'))
);

create table journal_connexion (
    id              bigserial primary key,
    utilisateur_id  bigint, -- nullable: failed login with unknown email
    email_tente     varchar(160) not null,
    adresse_ip      varchar(45),
    user_agent      text,
    succes          boolean      not null,
    horodatage      timestamptz  not null default now(),
    constraint fk_journal_connexion_utilisateur_id foreign key (utilisateur_id) references utilisateur(id)
);

-- Supports "lookup a user's login history" query.
create index idx_journal_connexion_utilisateur_id on journal_connexion (utilisateur_id);

create table refresh_token (
    id              bigserial primary key,
    utilisateur_id  bigint       not null,
    token_hash      varchar(255) not null unique,
    expire_at       timestamptz  not null,
    revoque         boolean      not null default false,
    cree_at         timestamptz  not null default now(),
    constraint fk_refresh_token_utilisateur_id foreign key (utilisateur_id) references utilisateur(id)
);

-- Supports "lookup a user's refresh tokens" query.
create index idx_refresh_token_utilisateur_id on refresh_token (utilisateur_id);

insert into utilisateur (email, mot_de_passe_hash, nom, role) values
    ('admin@sncft.tn',       '$2b$10$vOMfCxAk7fzLB0ME54KlTucnPNoEeue4y7ZbNRh2TL0QJJxMU29T2', 'Administrateur',           'ADMINISTRATEUR'),
    ('agent@sncft.tn',       '$2b$10$vOMfCxAk7fzLB0ME54KlTucnPNoEeue4y7ZbNRh2TL0QJJxMU29T2', 'Agent Circulation',        'AGENT_CIRCULATION'),
    ('responsable@sncft.tn', '$2b$10$vOMfCxAk7fzLB0ME54KlTucnPNoEeue4y7ZbNRh2TL0QJJxMU29T2', 'Responsable Exploitation', 'RESPONSABLE_EXPLOITATION'),
    ('voyageur@sncft.tn',    '$2b$10$vOMfCxAk7fzLB0ME54KlTucnPNoEeue4y7ZbNRh2TL0QJJxMU29T2', 'Voyageur',                 'VOYAGEUR');
