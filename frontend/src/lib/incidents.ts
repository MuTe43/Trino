import { ApiError, API_BASE_URL } from "./api";
import { authFetch } from "./auth";
import type {
  CorpsIncident,
  CorpsModificationIncident,
  ErreurApi,
  Gravite,
  IncidentDTO,
  PageDTO,
  StatutIncident,
} from "./types";

/**
 * Incident reads/writes. Separate module from `api.ts` for the same reason as
 * `tableauBord.ts`: every endpoint here needs the bearer token, and `api.ts`
 * cannot import `auth.ts` without a cycle (`auth.ts` already imports `api.ts`).
 *
 * Every endpoint is `AGENT_CIRCULATION` or `RESPONSABLE_EXPLOITATION` only
 * (resolution: `RESPONSABLE_EXPLOITATION` only), enforced server-side by both
 * the URL rule and `@PreAuthorize` (invariant 9). The client never decides
 * authorisation; it only renders what it is given, or the error it is handed.
 */

async function lireErreur(reponse: Response): Promise<ErreurApi | undefined> {
  try {
    return (await reponse.json()) as ErreurApi;
  } catch {
    return undefined;
  }
}

async function requeteAuth<T>(chemin: string, init: RequestInit = {}): Promise<T> {
  let reponse: Response;
  try {
    reponse = await authFetch(chemin, init);
  } catch {
    throw new ApiError(0, `Impossible de joindre l'API (${API_BASE_URL}${chemin}).`);
  }
  if (!reponse.ok) {
    const erreur = await lireErreur(reponse);
    throw new ApiError(
      reponse.status,
      erreur?.message ?? `Erreur API ${reponse.status} sur ${chemin}.`,
      erreur,
    );
  }
  return (await reponse.json()) as T;
}

/** Filters accepted by `GET /incidents`. All optional. */
export interface FiltresIncidents {
  statut?: StatutIncident;
  gravite?: Gravite;
  ligneId?: number;
  /** ISO-8601 with an offset. */
  depuis?: string;
  page?: number;
  taille?: number;
}

function requeteIncidents(filtres: FiltresIncidents): string {
  const parametres = new URLSearchParams();
  if (filtres.statut) parametres.set("statut", filtres.statut);
  if (filtres.gravite) parametres.set("gravite", filtres.gravite);
  if (filtres.ligneId !== undefined) parametres.set("ligneId", String(filtres.ligneId));
  if (filtres.depuis) parametres.set("depuis", filtres.depuis);
  parametres.set("page", String(filtres.page ?? 0));
  parametres.set("taille", String(filtres.taille ?? 20));
  return `?${parametres.toString()}`;
}

/** `GET /incidents` — the paged, filterable incident list behind the console. */
export function listerIncidents(filtres: FiltresIncidents = {}): Promise<PageDTO<IncidentDTO>> {
  return requeteAuth<PageDTO<IncidentDTO>>(`/incidents${requeteIncidents(filtres)}`);
}

/** `GET /incidents/ouverts` — every OUVERT/EN_COURS incident, for a map's
 * initial snapshot before the SSE deltas start. */
export function listerIncidentsOuverts(): Promise<IncidentDTO[]> {
  return requeteAuth<IncidentDTO[]>("/incidents/ouverts");
}

/** `POST /incidents` — declares a new incident, and via `courseId` optionally
 * changes that course's status and/or names its delay cause. */
export function declarerIncident(corps: CorpsIncident): Promise<IncidentDTO> {
  return requeteAuth<IncidentDTO>("/incidents", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(corps),
  });
}

/** `PATCH /incidents/{id}` — takes charge (`statut: "EN_COURS"`), or edits
 * gravité/description/impact. Never sends `statut: "RESOLU"` -- the server
 * rejects that with 403 by design, see `resoudreIncident`. */
export function modifierIncident(id: number, corps: CorpsModificationIncident): Promise<IncidentDTO> {
  return requeteAuth<IncidentDTO>(`/incidents/${id}`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(corps),
  });
}

/** `POST /incidents/{id}/resolution` — `RESPONSABLE_EXPLOITATION` only. */
export function resoudreIncident(id: number): Promise<IncidentDTO> {
  return requeteAuth<IncidentDTO>(`/incidents/${id}/resolution`, { method: "POST" });
}
