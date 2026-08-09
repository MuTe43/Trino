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
 * `event: incident`. PROVISIONAL: no `EvenementIncident` record exists yet
 * under backend/api/.../diffusion (the incidents feature lands in a later
 * phase — see docs/phases). Shaped from the `incident` table in
 * docs/architecture/domain-model.md pending the real backend record; update
 * this type the moment that record exists rather than trusting this shape.
 */
export interface EvenementIncident {
  id: number;
  type: TypeIncident;
  description: string;
  survenuAt: string;
  gare: GareBreve | null;
  ligne: LigneBreve | null;
  courseId: number | null;
  gravite: Gravite;
  impact: string | null;
  statut: StatutIncident;
  resoluAt: string | null;
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
 * `incidentsOuverts` / `incidentsResolus` are hardcoded 0 until phase 6 creates
 * the incident table. If phase 6 is cut, remove the two tiles rather than leave
 * them showing zero — a permanent zero reads as a broken feature.
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
