// Hand-written mirrors of the backend DTOs (see docs/architecture/api-contract.md
// and docs/architecture/domain-model.md). Do not generate these from a schema.

/** Paged response envelope used by every list endpoint. */
export interface PageDTO<T> {
  contenu: T[];
  page: number;
  taille: number;
  total: number;
}

/** Mirrors the `gare` table / GareDTO. */
export interface Gare {
  id: number;
  code: string;
  nom: string;
  region: string;
  latitude: number;
  longitude: number;
  nbQuais: number;
  responsable: string;
  actif: boolean;
}

/** Error envelope produced by ApiExceptionHandler for every 4xx/5xx. */
export interface ErreurApi {
  horodatage: string;
  statut: number;
  code:
    | "VALIDATION_ECHOUEE"
    | "NON_AUTHENTIFIE"
    | "ACCES_REFUSE"
    | "INTROUVABLE"
    | "CONFLIT"
    | "CLE_INGESTION_INVALIDE"
    | "TROP_DE_REQUETES"
    | "ERREUR_INTERNE";
  message: string;
  details?: { champ: string; probleme: string }[];
}

export type Role =
  | "VOYAGEUR"
  | "AGENT_CIRCULATION"
  | "RESPONSABLE_EXPLOITATION"
  | "ADMINISTRATEUR";

/** Mirrors the `utilisateur` table / UtilisateurDTO. */
export interface Utilisateur {
  id: number;
  email: string;
  nom: string;
  role: Role;
  actif: boolean;
}

/** Response body of POST /auth/login and /auth/refresh. */
export interface LoginResponse {
  accessToken: string;
  refreshToken: string;
  utilisateur: Utilisateur;
}

// ---------------------------------------------------------------------------
// Circulation & référentiel DTOs
// Mirrors of the Java records under backend/api/.../circulation/dto and
// .../referentiel/dto. All timestamps are ISO-8601 strings with an offset
// (OffsetDateTime on the wire) — never parsed or re-derived here. A client
// must never add `retardMin` to a `*Theorique` time to produce an estimate;
// it reads `*Estimee` / `*Estime` as the engine wrote it.
// ---------------------------------------------------------------------------

/** Rolling stock category. Mirrors referentiel/domaine/TypeTrain. */
export type TypeTrain = "EXPRESS" | "BANLIEUE" | "GRANDES_LIGNES" | "FRET";

/** Direction of a course along its ligne. Mirrors circulation/domaine/SensCourse. */
export type SensCourse = "ALLER" | "RETOUR";

/**
 * Status of a dated run. Mirrors circulation/domaine/StatutCourse's real
 * constants exactly — there is no `PLANIFIEE` value in the backend enum.
 */
export type StatutCourse =
  | "A_QUAI"
  | "EN_CIRCULATION"
  | "RETARDE"
  | "ARRET_EXCEPTIONNEL"
  | "ANNULE"
  | "TERMINUS_ATTEINT";

/** Why a course is late. Mirrors circulation/domaine/CauseRetard. */
export type CauseRetard =
  | "INCIDENT_TECHNIQUE"
  | "METEO"
  | "ACCIDENT"
  | "SIGNALISATION"
  | "TRAVAUX"
  | "ATTENTE_CORRESPONDANCE"
  | "AFFLUENCE_VOYAGEURS"
  | "AUTRE";

/**
 * Delay bucket. `ANNULE` is reserved for a cancelled course even though the
 * backend's `ClasseRetard.de(retardMin)` never emits it today (it only
 * derives A_L_HEURE..R60_PLUS from `retardMin`) — kept in the union so a
 * consumer's classeRetard -> couleur lookup is total and does not need a
 * separate branch on `statut === 'ANNULE'`.
 */
export type ClasseRetard =
  | "A_L_HEURE"
  | "R5"
  | "R10"
  | "R15"
  | "R30"
  | "R60_PLUS"
  | "ANNULE";

/** `{id, nom}` shape shared by the `ligne` field of CourseResumeDTO. */
export interface LigneBreve {
  id: number;
  nom: string;
}

/**
 * `{id, nom}` shape shared by CourseResumeDTO's garePrecedente/gareSuivante
 * and by PassageDTO's gare — the backend declares these as distinct nested
 * records (CourseResumeDTO.GareBreveDTO, PassageDTO.GareBreveDTO) but the
 * JSON shape is identical, so one TS type covers both.
 */
export interface GareBreve {
  id: number;
  nom: string;
}

/** Ground speed only, never used to derive an ETA. */
export interface PositionCourante {
  latitude: number;
  longitude: number;
  vitesseKmh: number;
}

/**
 * A course as every list and map panel shows it. Mirrors
 * circulation/dto/CourseResumeDTO. `position`, `garePrecedente`,
 * `gareSuivante` and `etaSuivante` are null for a course that has not yet
 * reported a position (hot state, lost on API restart until the next ping).
 */
export interface CourseResumeDTO {
  id: number;
  numeroTrain: string;
  nomTrain: string;
  type: TypeTrain;
  ligne: LigneBreve;
  sens: SensCourse;
  statut: StatutCourse;
  retardMin: number;
  classeRetard: ClasseRetard;
  causeRetard: CauseRetard | null;
  departTheorique: string;
  arriveeTheorique: string;
  position: PositionCourante | null;
  garePrecedente: GareBreve | null;
  gareSuivante: GareBreve | null;
  etaSuivante: string | null;
}

/**
 * One stop of a course. Mirrors circulation/dto/PassageDTO. Arrival fields
 * are null at the origin and departure fields are null at the terminus — a
 * train does not arrive at where it starts. `franchi` is
 * `arriveeReelle !== null`; render the real time for a franchi stop and the
 * estimate for the rest, never a theoretical time plus `retardMin`.
 */
export interface PassageDTO {
  ordre: number;
  gare: GareBreve;
  quai: string | null;
  arriveeTheorique: string | null;
  arriveeEstimee: string | null;
  arriveeReelle: string | null;
  departTheorique: string | null;
  departEstime: string | null;
  departReel: string | null;
  retardMin: number;
  classeRetard: ClasseRetard;
  franchi: boolean;
}

/**
 * One row of a station's departure board. Mirrors
 * circulation/dto/DepartGareDTO. `departTheorique` / `departEstime` are only
 * ever null-free rows on the wire (the query behind `/gares/{id}/departs`
 * only returns passages that have a departure), `departReel` stays null
 * until the train actually leaves. A cancelled course is still returned, as
 * a present-but-dead row.
 */
export interface DepartGareDTO {
  courseId: number;
  numeroTrain: string;
  nomTrain: string;
  type: TypeTrain;
  destination: string;
  quai: string | null;
  departTheorique: string;
  departEstime: string;
  departReel: string | null;
  statut: StatutCourse;
  retardMin: number;
  classeRetard: ClasseRetard;
}

/** Mirrors the `ligne` table / referentiel/dto/LigneDTO. */
export interface LigneDTO {
  id: number;
  code: string;
  nom: string;
  distanceKm: number;
  vitesseMaxKmh: number;
  tempsTheoriqueMin: number;
  /** [lon, lat] pairs, parsed server-side from the entity's raw jsonb trace. */
  trace: [number, number][];
  actif: boolean;
}

/**
 * One ordered stop of a ligne's theoretical stop pattern. Mirrors
 * referentiel/dto/DesserteDTO. `offsetArriveeMin` is null at the origin,
 * `offsetDepartMin` is null at the terminus.
 */
export interface DesserteDTO {
  id: number;
  ligneId: number;
  gareId: number;
  gareNom: string;
  ordre: number;
  pkKm: number | null;
  offsetArriveeMin: number | null;
  offsetDepartMin: number | null;
}

// ---------------------------------------------------------------------------
// SSE delta payloads (event names: position, statut, retard, incident)
// One EventSource per open channel — see src/lib/sse.ts. Deltas only, never
// a snapshot; the caller fetches the REST snapshot and patches these on top
// of the `Map<number, Course>` it already holds.
// ---------------------------------------------------------------------------

/** `event: position`. Mirrors circulation/evenement/EvenementPosition. */
export interface EvenementPosition {
  courseId: number;
  latitude: number;
  longitude: number;
  vitesseKmh: number;
  avancementKm: number;
  etaSuivante: string | null;
}

/** `event: statut`. Mirrors circulation/evenement/EvenementStatut. Emitted only
 * on an actual state machine transition, never on every ping. */
export interface EvenementStatut {
  courseId: number;
  statut: StatutCourse;
  retardMin: number;
  classeRetard: ClasseRetard;
  causeRetard: CauseRetard | null;
}

/** `event: retard`. Mirrors circulation/evenement/EvenementRetard. */
export interface EvenementRetard {
  courseId: number;
  retardMin: number;
  classeRetard: ClasseRetard;
  causeRetard: CauseRetard | null;
  /** Only the stops whose estimate actually moved — never the full passage list. */
  passagesRevises: PassageRevise[];
}

/** Mirrors circulation/evenement/EvenementRetard.PassageRevise. */
export interface PassageRevise {
  gareId: number;
  ordre: number;
  arriveeEstimee: string | null;
  departEstime: string | null;
  retardMin: number;
}

/** Gravity of a reported incident. Mirrors exploitation's Gravite enum. */
export type Gravite = "MINEURE" | "MOYENNE" | "MAJEURE" | "CRITIQUE";

/** Mirrors exploitation's StatutIncident enum. */
export type StatutIncident = "OUVERT" | "EN_COURS" | "RESOLU";

/** Mirrors exploitation's TypeIncident enum. */
export type TypeIncident =
  | "PANNE_LOCOMOTIVE"
  | "DEFAUT_SIGNALISATION"
  | "ACCIDENT"
  | "OBSTACLE_VOIE"
  | "INTEMPERIES"
  | "COUPURE_ELECTRIQUE"
  | "TRAVAUX"
  | "AUTRE";

/**
 * `event: incident`. Mirrors exploitation/evenement/EvenementIncident, confirmed
 * against a live `/stream` frame in phase 6 (see docs/phases/phase-6.md, "Three
 * things to close first" #1). Only the delta carries coordinates -- the REST
 * `IncidentDTO` below does not -- because a ligne-wide incident tied to a course
 * needs its point computed from that course's live position, which only the
 * streaming side has.
 *
 * `ligneId`/`gareId`/`courseId` are independently nullable: an incident is
 * required to carry at least one of gare/ligne/course (never all three), and
 * `ligneId` is populated even for a course-only incident (derived from the
 * course's ligne) — see the acceptance trace in phase-6.md.
 */
export interface EvenementIncident {
  incidentId: number;
  type: TypeIncident;
  gravite: Gravite;
  statut: StatutIncident;
  description: string;
  survenuAt: string;
  ligneId: number | null;
  gareId: number | null;
  courseId: number | null;
  /** Null for an incident with no single point (a ligne-wide incident with
   * neither gare nor a positioned course) -- render it in a list, never guess
   * a location for it. */
  latitude: number | null;
  longitude: number | null;
}

/** `{id, nom}` shape of `IncidentDTO.declarePar` — same shape as `GareBreve`/
 * `LigneBreve` but kept distinct since it names a person, not a référentiel row. */
export interface UtilisateurBref {
  id: number;
  nom: string;
}

/** `{id, numeroTrain}` shape of `IncidentDTO.course` — a course is identified
 * by its train number in the incidents console, not by its id. */
export interface CourseBreve {
  id: number;
  numeroTrain: string;
}

/**
 * `GET /incidents`, `/incidents/ouverts`, `/incidents/{id}` and the body of
 * `POST /incidents` / `PATCH /incidents/{id}` / `POST .../resolution`.
 * Mirrors exploitation/dto/IncidentDTO.
 *
 * `causeAssociee` is `TypeIncident.causeAssociee()` -- the `CauseRetard` this
 * type *suggests* on a linked course. It is returned so the declaration form
 * can preview it without the frontend restating the mapping table (see
 * docs/architecture/domain-model.md, "Incident type to delay cause"); it never
 * overwrites a `causeRetard` an agent set explicitly.
 */
export interface IncidentDTO {
  id: number;
  type: TypeIncident;
  causeAssociee: CauseRetard;
  description: string;
  survenuAt: string;
  gare: GareBreve | null;
  ligne: LigneBreve | null;
  course: CourseBreve | null;
  gravite: Gravite;
  impact: string;
  statut: StatutIncident;
  declarePar: UtilisateurBref;
  resoluAt: string | null;
}

/**
 * Body of `POST /incidents`. Mirrors exploitation/dto/IncidentCreateDTO.
 *
 * At least one of `gareId`/`ligneId`/`courseId` is required server-side (400
 * `VALIDATION_ECHOUEE`, `details[].champ === "localisationRenseignee"` when
 * none is set). `actionCourse` may only be `"ARRET_EXCEPTIONNEL"` or
 * `"ANNULE"` and requires `courseId` (`champ === "actionCourseRattachee"` /
 * `"actionCourseAutorisee"`); `causeRetard` also requires `courseId`
 * (`champ === "causeRattachee"`). `statut` is absent on purpose: a declared
 * incident is always `OUVERT`.
 */
export interface CorpsIncident {
  type: TypeIncident;
  description: string;
  /** ISO-8601 with an offset -- constructed client-side from a Africa/Tunis
   * wall-clock input, never the browser's own timezone. */
  survenuAt: string;
  gareId?: number;
  ligneId?: number;
  courseId?: number;
  gravite: Gravite;
  impact: string;
  actionCourse?: "ARRET_EXCEPTIONNEL" | "ANNULE";
  causeRetard?: CauseRetard;
}

/**
 * Body of `PATCH /incidents/{id}`. Mirrors exploitation/dto/IncidentUpdateDTO.
 * Every field is optional; a field left out is left alone. `statut` may only
 * ever be `"EN_COURS"` here -- resolving is the dedicated
 * `POST /incidents/{id}/resolution`, `RESPONSABLE_EXPLOITATION` only.
 */
export interface CorpsModificationIncident {
  statut?: "EN_COURS";
  gravite?: Gravite;
  description?: string;
  impact?: string;
}

// ---------------------------------------------------------------------------
// Tableau de bord (phase 5). Mirrors backend/api/.../analytique/dto.
// ---------------------------------------------------------------------------

/**
 * `GET /tableau-bord/kpi?date=`.
 *
 * `passagesMesures` is the denominator behind `tauxPonctualite`: it is zero
 * early in a service day, when nothing has been reached yet and a rate of 0
 * would read as total failure rather than as "no data". Render "—" in that
 * case, never "0 %".
 *
 * `voyageursImpactes` is ESTIMATED from train capacity on delayed courses. It
 * is modelled, never measured, and the UI has to say so.
 *
 * `incidentsOuverts` / `incidentsResolus` come from the `incident` table
 * (phase 6) and are real counts.
 */
export interface KpiJourDTO {
  date: string;
  trainsEnCirculation: number;
  nbRetards: number;
  retardMoyenMin: number;
  tauxPonctualite: number;
  passagesMesures: number;
  incidentsOuverts: number;
  incidentsResolus: number;
  trainsAnnules: number;
  voyageursImpactes: number;
}

export interface RetardParLigneDTO {
  ligneId: number;
  ligneNom: string;
  courses: number;
  coursesEnRetard: number;
  retardMoyenMin: number;
  retardMaxMin: number;
}

/** One cell of the gare x hour grid. `heure` is already in Africa/Tunis. */
export interface CaseHeatmapDTO {
  gareId: number;
  gareNom: string;
  heure: number;
  retardMoyenMin: number;
  passages: number;
}

export interface PointPonctualiteDTO {
  periode: string;
  passages: number;
  passagesPonctuels: number;
  tauxPonctualite: number;
  retardMoyenMin: number;
}

export type Granularite = "JOUR" | "MOIS";

/** One bar of the delay histogram. Buckets mirror `ClasseRetard` on the server. */
export interface BucketRetardDTO {
  classe: ClasseRetard;
  courses: number;
}

// ---------------------------------------------------------------------------
// Administration (phase 7)
// Mirrors of referentiel/dto/TrainDTO and the iam DTOs. Every endpoint behind
// these is ADMINISTRATEUR-only, enforced server-side by a URL rule AND
// @PreAuthorize (invariant 9); the client never decides authorisation.
// ---------------------------------------------------------------------------

/**
 * Rolling stock. Mirrors `referentiel/dto/TrainDTO`. It has no status and no
 * delay, and never will — those live on `Course` (invariant 1).
 */
export interface Train {
  id: number;
  numero: string;
  nom: string | null;
  type: TypeTrain;
  /** Nullable: a trainset need not be assigned to a ligne. */
  ligneId: number | null;
  capacite: number | null;
  vitesseMaxKmh: number | null;
  actif: boolean;
}

/**
 * Returned by `POST /utilisateurs` and `POST /utilisateurs/{id}/mot-de-passe`,
 * and by nothing else.
 *
 * `motDePasseInitial` is the only moment the plaintext exists: the server keeps
 * the BCrypt hash alone, so it cannot be read back afterwards — losing it means
 * re-issuing, not recovering. Any screen showing it must say so.
 *
 * It is *initial*, not *temporaire*: there is no forced-change-on-first-login
 * flow, so calling it temporary would promise something the system does not do.
 */
export interface UtilisateurCree extends Utilisateur {
  motDePasseInitial: string;
}

/** Body of `POST /utilisateurs`. No password field — the server generates it. */
export interface CorpsUtilisateurCreation {
  email: string;
  nom: string;
  role: Role;
}

/** Body of `PATCH /utilisateurs/{id}`. Absent field means unchanged; email is
 * immutable. Sending `actif: false` or a non-ADMINISTRATEUR `role` for your own
 * account is refused with 409 CONFLIT. */
export interface CorpsUtilisateurModification {
  nom?: string;
  role?: Role;
  actif?: boolean;
}

/**
 * One login attempt. Mirrors `iam/dto/JournalConnexionDTO`.
 *
 * `utilisateurId`/`utilisateurNom` are null when the attempted email matches no
 * account — which is most of what a failed-login list is for.
 */
export interface JournalConnexion {
  id: number;
  utilisateurId: number | null;
  utilisateurNom: string | null;
  emailTente: string;
  adresseIp: string | null;
  userAgent: string | null;
  succes: boolean;
  horodatage: string;
}

// ---------------------------------------------------------------------------
// Notifications et alertes (phase 8)
// Mirrors backend/api/.../notification/dto and .../notification/domaine.
//
// Nothing here carries the subscriber's token. It reaches the browser as an
// HttpOnly cookie set by POST /abonnements and is never readable from
// JavaScript, never sent in a URL, and never present in a response body — it is
// a bearer credential for one passenger's subscription list, not an id.
// ---------------------------------------------------------------------------

/** How a notification is delivered. Mirrors notification/domaine/CanalType. */
export type CanalType = "IN_APP" | "EMAIL" | "SMS" | "AFFICHAGE";

/** What a subscription follows. Mirrors notification/domaine/CibleType. */
export type CibleType = "COURSE" | "LIGNE" | "GARE";

/** What an alert rule reacts to. Mirrors notification/domaine/Evenement. */
export type EvenementAlerte =
  | "RETARD_SEUIL"
  | "COURSE_ANNULEE"
  | "INCIDENT_DECLARE"
  | "INCIDENT_RESOLU";

/** Mirrors notification/domaine/StatutNotification. `EN_ATTENTE` means the
 * dispatch is still in flight, `ECHEC` that a working channel could not
 * deliver — the reason is kept server-side, not shown to a passenger. */
export type StatutNotification = "EN_ATTENTE" | "ENVOYE" | "ECHEC";

/** One subscription, as its owner sees it. Mirrors notification/dto/AbonnementDTO. */
export interface AbonnementDTO {
  id: number;
  cibleType: CibleType;
  cibleId: number;
  canaux: CanalType[];
  email: string | null;
  creeAt: string;
}

/**
 * Body of `POST /abonnements`. No identity field: whose subscription this is
 * comes from the caller's own cookie, server-side.
 *
 * `email` is required whenever `canaux` contains `EMAIL`; the server reports the
 * violation on `emailRequisPourCanalEmail`, which is the one `details[].champ`
 * in the API that is not a plain field name.
 */
export interface CorpsAbonnement {
  cibleType: CibleType;
  cibleId: number;
  canaux: CanalType[];
  email?: string;
}

/**
 * One emitted notification. Mirrors notification/dto/NotificationDTO.
 *
 * `envoyeAt` is null while the dispatch is in flight, which is why lists here
 * order on `id` — monotonic, never null, and on an append-only table it is the
 * emission order.
 */
export interface NotificationDTO {
  id: number;
  evenement: EvenementAlerte;
  courseId: number | null;
  canal: CanalType;
  sujet: string;
  contenu: string;
  statut: StatutNotification;
  envoyeAt: string | null;
}

/**
 * `event: notification`, on the caller's own `abonne:` channel. Mirrors
 * notification/evenement/EvenementNotification.
 *
 * The frame is tagged with the alias `abonne:moi`, never with the real channel
 * name — that name embeds the token, and echoing it back would hand a scripts-
 * on-the-page-readable copy of the HttpOnly cookie to anything listening.
 */
export interface EvenementNotification {
  notificationId: number;
  evenement: EvenementAlerte;
  sujet: string;
  contenu: string;
  courseId: number | null;
  emisAt: string | null;
}

/**
 * One alert rule. Mirrors notification/dto/RegleAlerteDTO.
 *
 * `seuilMin` is set for `RETARD_SEUIL` and null for every other event;
 * `graviteMin` is null for "every severity". `modifiePar` is null for a rule
 * still as the migration seeded it — nobody has decided anything about it yet.
 */
export interface RegleAlerteDTO {
  id: number;
  evenement: EvenementAlerte;
  seuilMin: number | null;
  graviteMin: Gravite | null;
  canaux: CanalType[];
  actif: boolean;
  modifiePar: number | null;
  modifieParNom: string | null;
}

/** Body of `POST /regles-alerte`. */
export interface CorpsRegleAlerte {
  evenement: EvenementAlerte;
  seuilMin: number | null;
  graviteMin: Gravite | null;
  canaux: CanalType[];
  actif: boolean;
}

/** Body of `PATCH /regles-alerte/{id}`. Absent field means unchanged, and
 * `evenement` is absent on purpose: changing which event a rule reacts to is a
 * different rule, not an edit. */
export interface CorpsModificationRegleAlerte {
  seuilMin?: number | null;
  graviteMin?: Gravite | null;
  /** "Toutes les gravités". An absent field means unchanged, so a null
   * `graviteMin` cannot express "clear it" — the two are the same JSON. Send
   * this instead; without it the server keeps the old severity and answers 200. */
  effacerGraviteMin?: boolean;
  canaux?: CanalType[];
  actif?: boolean;
}
