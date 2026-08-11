import { requeteAuthJson } from "./auth";
import type {
  CorpsUtilisateurCreation,
  CorpsUtilisateurModification,
  DesserteDTO,
  Gare,
  JournalConnexion,
  LigneDTO,
  PageDTO,
  Train,
  TypeTrain,
  Utilisateur,
  UtilisateurCree,
} from "./types";

/**
 * Every call the administration console makes.
 *
 * Gares, lignes and trains are readable anonymously (the passenger portal reads
 * them), but the console routes its reads through the authenticated helper too:
 * one error path for the whole screen, and a 401 here means the session lapsed,
 * which is worth surfacing the same way everywhere.
 *
 * Authorisation is never decided here. Both a URL rule and `@PreAuthorize` gate
 * these server-side (invariant 9); the client renders what it is given, or the
 * error envelope it is handed.
 */

/** Builds a query string, dropping null/undefined/"" entries. */
function requete(params: Record<string, unknown>): string {
  const recherche = new URLSearchParams();
  for (const [cle, valeur] of Object.entries(params)) {
    if (valeur === undefined || valeur === null || valeur === "") continue;
    recherche.set(cle, String(valeur));
  }
  const chaine = recherche.toString();
  return chaine ? `?${chaine}` : "";
}

function corpsJson(methode: string, corps: unknown): RequestInit {
  return {
    method: methode,
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(corps),
  };
}

// --- Gares -----------------------------------------------------------------

export interface FiltresGares {
  region?: string;
  q?: string;
  page?: number;
  taille?: number;
}

/**
 * What a gare write actually accepts. Deliberately **not** `Omit<Gare, "id">`:
 * the nullable columns must be able to travel as `null`.
 *
 * Coercing an empty field to `0` or `""` on the way out looks harmless and is
 * not. `latitude`/`longitude` are `@NotNull` server-side with no range check, so
 * a blank coordinate coerced to `0` is *accepted* — and the public map extends
 * its initial bounds over every gare, so one station at (0, 0) zooms the
 * passenger map out to the Gulf of Guinea. `null` is refused with a 400 that the
 * dialog renders on the offending field, which is the whole point of having one.
 */
export interface CorpsGare {
  code: string;
  nom: string;
  region: string | null;
  latitude: number | null;
  longitude: number | null;
  nbQuais: number | null;
  responsable: string | null;
  actif: boolean;
}

export function listerGaresAdmin(filtres: FiltresGares = {}): Promise<PageDTO<Gare>> {
  return requeteAuthJson<PageDTO<Gare>>(`/gares${requete({ ...filtres })}`);
}

/** `POST /gares`. */
export function creerGare(corps: CorpsGare): Promise<Gare> {
  return requeteAuthJson<Gare>("/gares", corpsJson("POST", corps));
}

/** `PUT /gares/{id}` — a full replacement, so every field must be sent, not a diff. */
export function modifierGare(id: number, corps: CorpsGare): Promise<Gare> {
  return requeteAuthJson<Gare>(`/gares/${id}`, corpsJson("PUT", corps));
}

/** `DELETE /gares/{id}` — 409 CONFLIT when a desserte or a course still points at it. */
export function supprimerGare(id: number): Promise<void> {
  return requeteAuthJson<void>(`/gares/${id}`, { method: "DELETE" });
}

// --- Lignes ----------------------------------------------------------------

export function listerLignesAdmin(page = 0, taille = 100): Promise<PageDTO<LigneDTO>> {
  return requeteAuthJson<PageDTO<LigneDTO>>(`/lignes${requete({ page, taille })}`);
}

/**
 * `PUT /lignes/{id}`.
 *
 * `trace` is **not** editable in this console — a textarea of coordinate pairs
 * is a loaded gun pointed at the map. But PUT replaces the whole resource and
 * the server requires a polyline of at least two [lon, lat] points, so the
 * caller has to hand back the trace it loaded, untouched. Omit it and a rename
 * fails with a validation error on a field the user cannot even see.
 */
export function modifierLigne(id: number, corps: CorpsLigne): Promise<LigneDTO> {
  return requeteAuthJson<LigneDTO>(`/lignes/${id}`, corpsJson("PUT", corps));
}

/** Same reason as {@link CorpsGare}: the optional numbers travel as `null`, not
 * as a `0` that would silently overwrite a real value with a wrong one. */
export interface CorpsLigne {
  code: string;
  nom: string;
  distanceKm: number | null;
  vitesseMaxKmh: number | null;
  tempsTheoriqueMin: number | null;
  /** Resent exactly as loaded — see the note on `modifierLigne`. */
  trace: [number, number][];
  actif: boolean;
}

/** `DELETE /lignes/{id}` — 409 CONFLIT when courses, trains or a desserte reference it. */
export function supprimerLigne(id: number): Promise<void> {
  return requeteAuthJson<void>(`/lignes/${id}`, { method: "DELETE" });
}

/** `GET /lignes/{id}/desserte` — read-only here: reordering stops breaks the
 * `pk_km` monotonicity the delay engine assumes. */
export function listerDesserteAdmin(ligneId: number): Promise<DesserteDTO[]> {
  return requeteAuthJson<DesserteDTO[]>(`/lignes/${ligneId}/desserte`);
}

// --- Trains ----------------------------------------------------------------

export interface FiltresTrains {
  type?: TypeTrain;
  ligneId?: number;
  page?: number;
  taille?: number;
}

export function listerTrainsAdmin(filtres: FiltresTrains = {}): Promise<PageDTO<Train>> {
  return requeteAuthJson<PageDTO<Train>>(`/trains${requete({ ...filtres })}`);
}

export function creerTrain(corps: Omit<Train, "id">): Promise<Train> {
  return requeteAuthJson<Train>("/trains", corpsJson("POST", corps));
}

export function modifierTrain(id: number, corps: Omit<Train, "id">): Promise<Train> {
  return requeteAuthJson<Train>(`/trains/${id}`, corpsJson("PUT", corps));
}

export function supprimerTrain(id: number): Promise<void> {
  return requeteAuthJson<void>(`/trains/${id}`, { method: "DELETE" });
}

// --- Utilisateurs ----------------------------------------------------------

export function listerUtilisateurs(page = 0, taille = 20): Promise<PageDTO<Utilisateur>> {
  return requeteAuthJson<PageDTO<Utilisateur>>(`/utilisateurs${requete({ page, taille })}`);
}

/**
 * `POST /utilisateurs`. The response carries `motDePasseInitial` — the only
 * time the plaintext exists. Show it once, say it will not be shown again.
 */
export function creerUtilisateur(corps: CorpsUtilisateurCreation): Promise<UtilisateurCree> {
  return requeteAuthJson<UtilisateurCree>("/utilisateurs", corpsJson("POST", corps));
}

/** `PATCH /utilisateurs/{id}`. 409 CONFLIT if an admin deactivates their own
 * account or moves their own role off ADMINISTRATEUR. */
export function modifierUtilisateur(
  id: number,
  corps: CorpsUtilisateurModification,
): Promise<Utilisateur> {
  return requeteAuthJson<Utilisateur>(`/utilisateurs/${id}`, corpsJson("PATCH", corps));
}

/** `POST /utilisateurs/{id}/mot-de-passe` — re-issues, never reveals the old one. */
export function reinitialiserMotDePasse(id: number): Promise<UtilisateurCree> {
  return requeteAuthJson<UtilisateurCree>(`/utilisateurs/${id}/mot-de-passe`, { method: "POST" });
}

// --- Journal de connexions -------------------------------------------------

export interface FiltresJournal {
  succes?: boolean;
  utilisateurId?: number;
  /** Plain `YYYY-MM-DD`, bucketed server-side in Africa/Tunis (invariant 6). */
  du?: string;
  au?: string;
  page?: number;
  taille?: number;
}

export function listerJournal(filtres: FiltresJournal = {}): Promise<PageDTO<JournalConnexion>> {
  return requeteAuthJson<PageDTO<JournalConnexion>>(`/journal-connexions${requete({ ...filtres })}`);
}
