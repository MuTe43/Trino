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
