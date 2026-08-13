import { API_BASE_URL, ApiError } from "./api";
import type {
  AbonnementDTO,
  CorpsAbonnement,
  ErreurApi,
  NotificationDTO,
  PageDTO,
} from "./types";

/**
 * The passenger side of notifications: following a train, and reading what came
 * back.
 *
 * The identity is a cookie and nothing else. `POST /abonnements` mints it on a
 * first-time visitor and returns it as an `HttpOnly` cookie, so this module
 * never sees the token, never stores it, and never puts it in a URL — every call
 * simply sends `credentials: "include"` and lets the server work out whose
 * subscriptions these are. That is also what binds the SSE stream: the
 * `abonne:` channel is derived server-side from the same cookie, so there is no
 * request a client can make that reads someone else's notifications.
 *
 * The cookie is `SameSite=Lax`, which is enough here even though the portal is
 * on :3000 and the API on :8080 — SameSite is a *site* rule and ignores the
 * port, so these are same-site requests. `SameSite=None` would have required
 * `Secure`, and over plain http on localhost the browser drops such a cookie
 * outright.
 */

/**
 * The channel name the client subscribes to for its own notifications.
 *
 * An alias: the server tags every `abonne:` frame with this instead of the real
 * channel name, which embeds the token. `sse.ts` recognises it and opens the
 * stream without naming it in the query string — the whole point being that the
 * client does not get to choose whose channel it listens on.
 */
export const CANAL_ABONNE = "abonne:moi";

/**
 * Fired on `window` after a successful subscribe or unsubscribe.
 *
 * The bell lives in the public header and the button lives on a train page;
 * they are mounted in different subtrees with no shared provider, and a context
 * would only join what sits under it. One event on `window` is the smallest
 * thing that reaches both.
 */
export const EVENEMENT_ABONNEMENTS_MODIFIES = "trino:abonnements-modifies";

export function signalerChangementAbonnements(): void {
  if (typeof window !== "undefined") {
    window.dispatchEvent(new Event(EVENEMENT_ABONNEMENTS_MODIFIES));
  }
}

/** Same error shape as `apiGet`, with the cookie attached. */
async function appel<T>(chemin: string, init?: RequestInit): Promise<T> {
  const url = `${API_BASE_URL}/api/v1${chemin}`;

  let reponse: Response;
  try {
    reponse = await fetch(url, {
      ...init,
      // The whole identity mechanism. Without this the request is anonymous and
      // the server answers with an empty list rather than an error, which is
      // the hardest kind of bug to notice.
      credentials: "include",
      cache: "no-store",
    });
  } catch {
    throw new ApiError(0, `Impossible de joindre l'API (${url}).`);
  }

  if (!reponse.ok) {
    let erreur: ErreurApi | undefined;
    try {
      erreur = (await reponse.json()) as ErreurApi;
    } catch {
      erreur = undefined;
    }
    throw new ApiError(
      reponse.status,
      erreur?.message ?? `Erreur API ${reponse.status} sur ${url}.`,
      erreur,
    );
  }

  if (reponse.status === 204) {
    return undefined as T;
  }
  return (await reponse.json()) as T;
}

/**
 * `POST /abonnements` — follow a course, a ligne or a gare.
 *
 * 201 on a new subscription and 200 when this visitor already followed the same
 * target; both are successes and neither is distinguished here, because
 * pressing "Suivre" twice is not a thing a passenger needs to be told about.
 */
export async function suivre(corps: CorpsAbonnement): Promise<AbonnementDTO> {
  const abonnement = await appel<AbonnementDTO>("/abonnements", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(corps),
  });
  signalerChangementAbonnements();
  return abonnement;
}

/** `DELETE /abonnements/{id}` — scoped server-side; someone else's id is a 404. */
export async function nePlusSuivre(id: number): Promise<void> {
  await appel<void>(`/abonnements/${id}`, { method: "DELETE" });
  signalerChangementAbonnements();
}

/** `GET /abonnements/miennes` — empty for a visitor who has never subscribed. */
export function listerMesAbonnements(): Promise<AbonnementDTO[]> {
  return appel<AbonnementDTO[]>("/abonnements/miennes");
}

/** `GET /notifications` — newest first, paged. */
export function listerNotifications(page = 0, taille = 20): Promise<PageDTO<NotificationDTO>> {
  return appel<PageDTO<NotificationDTO>>(`/notifications?page=${page}&taille=${taille}`);
}
