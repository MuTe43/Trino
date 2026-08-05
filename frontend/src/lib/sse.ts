"use client";

import { useEffect, useRef, useState } from "react";
import { API_BASE_URL } from "./api";
import type {
  EvenementIncident,
  EvenementPosition,
  EvenementRetard,
  EvenementStatut,
} from "./types";

/**
 * The only place in the app allowed to `new EventSource(...)`. Every live
 * channel (map panel, station board, course detail) goes through
 * `useFluxSse`. This is deliberate: one hook owns reconnect/backoff/cleanup
 * once, instead of every consumer re-inventing it slightly differently.
 */

/** Discreet connection indicator for the board — never an error banner. */
export type EtatFluxSse = "connexion" | "ouvert" | "ferme";

/** One handler per SSE event name. All optional; a channel that only cares
 * about `retard` need not pass `onPosition`. */
export interface GestionnairesFluxSse {
  onPosition?: (evenement: EvenementPosition) => void;
  onStatut?: (evenement: EvenementStatut) => void;
  onRetard?: (evenement: EvenementRetard) => void;
  onIncident?: (evenement: EvenementIncident) => void;
  /** Called whenever the connection state changes, e.g. to drive a small dot. */
  onEtat?: (etat: EtatFluxSse) => void;
}

const DELAI_INITIAL_MS = 1_000;
const DELAI_MAX_MS = 30_000;

/** `ligne:3` -> `/stream/lignes/3`, `gare:1` -> `/stream/gares/1`. */
function urlDuCanal(canal: string): string | null {
  const [type, id] = canal.split(":");
  if (type === "ligne" && id) {
    return `${API_BASE_URL}/api/v1/stream/lignes/${id}`;
  }
  if (type === "gare" && id) {
    return `${API_BASE_URL}/api/v1/stream/gares/${id}`;
  }
  return null;
}

/**
 * Opens (at most) one `EventSource` for `canal` (`"ligne:3"` or `"gare:1"`),
 * applies deltas via `gestionnaires`, and keeps it alive across reconnects,
 * tab suspension and re-renders.
 *
 * `canal === null` opens nothing and closes whatever was open.
 *
 * Handlers are read through a ref, not captured in the effect that opens the
 * connection: a caller passing a fresh handler object every render (common
 * with inline arrow functions) must never cause a second `EventSource` to
 * open. Only a change in `canal` itself reopens the connection.
 */
export function useFluxSse(canal: string | null, gestionnaires: GestionnairesFluxSse): void {
  const gestionnairesRef = useRef(gestionnaires);
  // In an effect, not during render: a concurrent render that React discards
  // must not leave the ref pointing at handlers from a render that never
  // committed. The connection effect below reads it only on incoming events,
  // which always happen after commit.
  useEffect(() => {
    gestionnairesRef.current = gestionnaires;
  });

  useEffect(() => {
    if (canal === null) {
      return;
    }
    const url = urlDuCanal(canal);
    if (url === null) {
      return;
    }

    let source: EventSource | null = null;
    let delaiReconnexion = DELAI_INITIAL_MS;
    let minuteurReconnexion: ReturnType<typeof setTimeout> | null = null;
    let dernierEventId: string | undefined;
    let demonte = false;

    const signalerEtat = (etat: EtatFluxSse) => {
      if (!demonte) {
        gestionnairesRef.current.onEtat?.(etat);
      }
    };

    const annulerReconnexion = () => {
      if (minuteurReconnexion !== null) {
        clearTimeout(minuteurReconnexion);
        minuteurReconnexion = null;
      }
    };

    const fermerSource = () => {
      if (source !== null) {
        // Close before opening the next one: never leave two EventSources
        // racing against the same channel.
        source.close();
        source = null;
      }
    };

    const planifierReconnexion = () => {
      annulerReconnexion();
      // Full jitter: a random delay in [0, delaiReconnexion], so many tabs
      // reconnecting after a shared outage do not all hit the server at once.
      const delai = Math.random() * delaiReconnexion;
      delaiReconnexion = Math.min(delaiReconnexion * 2, DELAI_MAX_MS);
      minuteurReconnexion = setTimeout(ouvrir, delai);
    };

    // Manually recreating the EventSource on every reconnect (needed for our
    // own backoff curve) forfeits the browser's native behaviour of resending
    // the Last-Event-ID header on an *automatic* same-object retry. We carry
    // it forward ourselves as a query param instead, so a future HubSse that
    // gains replay support has something to read; today's HubSse ignores it
    // (no replay, deltas only), so this is a no-op server-side but keeps the
    // client honest about tracking its own read position per channel.
    const urlAvecReprise = () =>
      dernierEventId ? `${url}?lastEventId=${encodeURIComponent(dernierEventId)}` : url;

    const ouvrir = () => {
      if (demonte) {
        return;
      }
      fermerSource();
      signalerEtat("connexion");

      const nouvelleSource = new EventSource(urlAvecReprise(), { withCredentials: false });
      source = nouvelleSource;

      nouvelleSource.onopen = () => {
        delaiReconnexion = DELAI_INITIAL_MS;
        signalerEtat("ouvert");
      };

      nouvelleSource.onerror = () => {
        // The browser already retries a CONNECTING EventSource on its own,
        // but we take over explicitly so the backoff curve is ours (~1s
        // doubling to a 30s cap with jitter) rather than the UA default.
        if (demonte) {
          return;
        }
        signalerEtat("connexion");
        fermerSource();
        planifierReconnexion();
      };

      function ecouter<T>(nom: string, gestionnaire: ((donnees: T) => void) | undefined) {
        nouvelleSource.addEventListener(nom, (evenement) => {
          const messageEvenement = evenement as MessageEvent<string>;
          if (messageEvenement.lastEventId) {
            dernierEventId = messageEvenement.lastEventId;
          }
          if (!gestionnaire) {
            return;
          }
          try {
            gestionnaire(JSON.parse(messageEvenement.data) as T);
          } catch {
            // A malformed delta must not take the whole stream down.
          }
        });
      }

      ecouter<EvenementPosition>("position", gestionnairesRef.current.onPosition);
      ecouter<EvenementStatut>("statut", gestionnairesRef.current.onStatut);
      ecouter<EvenementRetard>("retard", gestionnairesRef.current.onRetard);
      ecouter<EvenementIncident>("incident", gestionnairesRef.current.onIncident);
    };

    ouvrir();

    const surVisibilite = () => {
      if (document.visibilityState !== "visible") {
        return;
      }
      // A frozen/dropped background tab may leave `source` in any state; if
      // it is not OPEN, reconnect immediately and reset the backoff instead
      // of waiting out whatever delay was in flight.
      if (source === null || source.readyState !== EventSource.OPEN) {
        annulerReconnexion();
        delaiReconnexion = DELAI_INITIAL_MS;
        ouvrir();
      }
    };
    document.addEventListener("visibilitychange", surVisibilite);

    return () => {
      demonte = true;
      document.removeEventListener("visibilitychange", surVisibilite);
      annulerReconnexion();
      fermerSource();
      signalerEtat("ferme");
    };
    // Reopens only when the channel itself changes. Handler identity changes
    // are read through gestionnairesRef and must never reopen the connection.
  }, [canal]);
}

/**
 * Convenience wrapper around `useFluxSse` that also exposes the connection
 * state as render-triggering state, for a small "connexion / ouvert / ferme"
 * indicator. Reconnects are silent otherwise — no error banner, no layout
 * shift; this is purely a discreet dot.
 */
export function useEtatFluxSse(canal: string | null, gestionnaires: GestionnairesFluxSse) {
  const [etat, setEtat] = useState<EtatFluxSse>("ferme");

  useFluxSse(canal, {
    ...gestionnaires,
    onEtat: (nouvelEtat) => {
      setEtat(nouvelEtat);
      gestionnaires.onEtat?.(nouvelEtat);
    },
  });

  return etat;
}
