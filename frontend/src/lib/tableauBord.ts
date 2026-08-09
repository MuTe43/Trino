import { ApiError, API_BASE_URL } from "./api";
import { authFetch, getAccessToken } from "./auth";
import type {
  BucketRetardDTO,
  CaseHeatmapDTO,
  ErreurApi,
  Granularite,
  KpiJourDTO,
  PointPonctualiteDTO,
  RetardParLigneDTO,
} from "./types";

/**
 * Dashboard reads. Separate module from `api.ts` because these all need a
 * bearer token, and `api.ts` cannot import `auth.ts` -- `auth.ts` already
 * imports `api.ts`, and the cycle would be resolved at runtime in whichever
 * order the bundler happened to pick.
 *
 * Every endpoint here is RESPONSABLE_EXPLOITATION only, enforced server-side.
 * The client never decides authorisation; it only renders what it is given, or
 * the error it is handed.
 */

async function lireErreur(reponse: Response): Promise<ErreurApi | undefined> {
  try {
    return (await reponse.json()) as ErreurApi;
  } catch {
    return undefined;
  }
}

async function getAuth<T>(chemin: string): Promise<T> {
  let reponse: Response;
  try {
    reponse = await authFetch(chemin);
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

export function chargerKpi(date: string): Promise<KpiJourDTO> {
  return getAuth<KpiJourDTO>(`/tableau-bord/kpi?date=${date}`);
}

export function chargerRetardsParLigne(date: string): Promise<RetardParLigneDTO[]> {
  return getAuth<RetardParLigneDTO[]>(`/tableau-bord/retards-par-ligne?date=${date}`);
}

export function chargerHeatmap(du: string, au: string): Promise<CaseHeatmapDTO[]> {
  return getAuth<CaseHeatmapDTO[]>(`/tableau-bord/heatmap?du=${du}&au=${au}`);
}

export function chargerDistributionRetards(du: string, au: string): Promise<BucketRetardDTO[]> {
  return getAuth<BucketRetardDTO[]>(`/tableau-bord/distribution-retards?du=${du}&au=${au}`);
}

export function chargerPonctualite(
  du: string,
  au: string,
  granularite: Granularite = "JOUR",
): Promise<PointPonctualiteDTO[]> {
  return getAuth<PointPonctualiteDTO[]>(
    `/rapports/ponctualite?du=${du}&au=${au}&granularite=${granularite}`,
  );
}

/**
 * Downloads an export.
 *
 * Not a plain `<a href>`: the endpoint needs an Authorization header, and an
 * anchor cannot carry one. The response is fetched, turned into a blob and
 * handed to a synthetic anchor, which is also what lets the server's
 * `Content-Disposition` filename survive.
 */
export async function telechargerExport(
  nom: string,
  du: string,
  au: string,
  format: "csv" | "xlsx",
): Promise<void> {
  const reponse = await authFetch(
    `/rapports/${nom}/export?du=${du}&au=${au}&format=${format}`,
  );
  if (!reponse.ok) {
    const erreur = await lireErreur(reponse);
    throw new ApiError(reponse.status, erreur?.message ?? "L'export a échoué.", erreur);
  }

  const blob = await reponse.blob();
  const url = URL.createObjectURL(blob);
  const lien = document.createElement("a");
  lien.href = url;
  lien.download = nomDepuisEntete(reponse) ?? `trino-${nom}-${du}-${au}.${format}`;
  document.body.appendChild(lien);
  lien.click();
  lien.remove();
  // Released on the next tick rather than immediately: revoking synchronously
  // after click() can cancel the download in some browsers.
  setTimeout(() => URL.revokeObjectURL(url), 1_000);
}

function nomDepuisEntete(reponse: Response): string | null {
  const entete = reponse.headers.get("Content-Disposition");
  if (!entete) return null;
  const correspondance = /filename="?([^";]+)"?/.exec(entete);
  return correspondance ? correspondance[1] : null;
}

/** True when a token is held in memory — used to avoid a pointless first call. */
export function estAuthentifie(): boolean {
  return getAccessToken() !== null;
}
