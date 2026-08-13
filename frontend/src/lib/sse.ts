"use client";

import { useEffect, useState } from "react";
import { CANAL_ABONNE } from "./abonnement";
import { API_BASE_URL } from "./api";
import type {
  EvenementIncident,
  EvenementNotification,
  EvenementPosition,
  EvenementRetard,
  EvenementStatut,
} from "./types";

/**
 * The only place in the app allowed to `new EventSource(...)`. Every live
 * channel (map panel, station board, course detail) goes through
 * `useFluxSse`.
 *
 * Phase 5 changed what that means underneath. There used to be one
 * `EventSource` per channel, which put the map at five connections on the
 * default view -- against a browser budget of about six per origin over
 * HTTP/1.1, shared with every REST call to the same origin. Measured: at six
 * channels the next REST request is never served and the app appears to hang.
 * Now every channel this tab wants rides one connection to
 * `/stream?lignes=&gares=`, and each frame carries its own channel identity.
 *
 * Consumers did not change: `useFluxSse(canal, gestionnaires)` has the same
 * shape it always had. The multiplexing lives entirely in this module.
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
  /** Only ever delivered on `CANAL_ABONNE` — this tab's own notifications. */
  onNotification?: (evenement: EvenementNotification) => void;
  /** Called whenever the connection state changes, e.g. to drive a small dot. */
  onEtat?: (etat: EtatFluxSse) => void;
}

const DELAI_INITIAL_MS = 1_000;
const DELAI_MAX_MS = 30_000;

/**
 * How long to wait after a subscription change before reopening the stream.
 *
 * A page mounting subscribes channel by channel -- the map adds one per visible
 * ligne as its effects run -- and reopening on each would tear the connection
 * down and back up several times during a single render pass. One frame's worth
 * of delay coalesces them into a single connect.
 */
const DELAI_REGROUPEMENT_MS = 50;

/**
 * `ligne:3` and `gare:1` -> `?lignes=3&gares=1`.
 *
 * `abonne:moi` contributes **no parameter at all**, and that is the point. The
 * server derives that channel from the caller's own cookie and ignores anything
 * the client says about it, so a subscription list naming another subscriber's
 * token cannot exist. It still counts as a channel here: a page whose only live
 * consumer is the notification bell opens `/stream` with an empty query, and the
 * server accepts it precisely because the cookie supplies one.
 */
function construireUrl(canaux: Iterable<string>): string | null {
  const lignes: string[] = [];
  const gares: string[] = [];
  let abonne = false;
  for (const canal of canaux) {
    if (canal === CANAL_ABONNE) {
      abonne = true;
      continue;
    }
    const [type, id] = canal.split(":");
    if (type === "ligne" && id) lignes.push(id);
    else if (type === "gare" && id) gares.push(id);
  }
  if (lignes.length === 0 && gares.length === 0 && !abonne) {
    return null;
  }
  const parametres = new URLSearchParams();
  // Sorted so the same channel set always produces the same URL, whatever
  // order the subscriptions arrived in. Without this an identical set could
  // look like a change and trigger a needless reconnect.
  if (lignes.length > 0) parametres.set("lignes", lignes.sort().join(","));
  if (gares.length > 0) parametres.set("gares", gares.sort().join(","));
  const requete = parametres.toString();
  return `${API_BASE_URL}/api/v1/stream${requete ? `?${requete}` : ""}`;
}

/**
 * The envelope the multiplexed endpoint wraps every delta in.
 *
 * `canaux` is a list: one delta legitimately concerns several of this tab's
 * channels at once (a course publishes to its ligne and to each gare ahead of
 * it), and the frame is sent once carrying all of them. Dispatching on only the
 * first would silently starve every other consumer.
 */
interface EnveloppeSse<T> {
  canaux: string[];
  donnees: T;
}

type NomEvenement = "position" | "statut" | "retard" | "incident" | "notification";

/**
 * One connection for the whole tab, and the book-keeping to decide what it
 * should be subscribed to.
 *
 * Module-level rather than a React context: the station board, the map and a
 * course detail panel can be mounted under different subtrees, and they still
 * have to share the one connection. A context would only merge what sits under
 * the same provider.
 */
class ConnexionPartagee {
  private readonly abonnes = new Map<string, Set<GestionnairesFluxSse>>();

  private source: EventSource | null = null;

  /**
   * The previous connection, kept alive and still delivering until the
   * replacement is open ("make before break").
   *
   * Changing the channel set means a new URL, and a new URL means a new
   * EventSource. Closing the old one first opens a gap in which deltas for the
   * channels that did NOT change are lost, with no way to recover them: no
   * frame carries an `id`, so there is no replay, and nothing refetches a
   * snapshot on reconnect. That gap would open every time the map pans a new
   * ligne into view — precisely the screen where it would be noticed.
   *
   * The overlap can deliver the same delta twice. That is harmless here: every
   * delta is a last-write-wins state update (a position, a status, a revised
   * estimate), never an increment.
   */
  private sortante: EventSource | null = null;

  private urlCourante: string | null = null;
  private delaiReconnexion = DELAI_INITIAL_MS;
  private minuteurReconnexion: ReturnType<typeof setTimeout> | null = null;
  private minuteurRegroupement: ReturnType<typeof setTimeout> | null = null;
  private ecouteVisibilite = false;

  /**
   * Ref-counted: two components watching `ligne:3` share one entry, and the
   * channel is only dropped from the subscription when the last of them goes.
   * Returns the unsubscribe.
   */
  abonner(canal: string, gestionnaires: GestionnairesFluxSse): () => void {
    let pourCeCanal = this.abonnes.get(canal);
    if (!pourCeCanal) {
      pourCeCanal = new Set();
      this.abonnes.set(canal, pourCeCanal);
    }
    pourCeCanal.add(gestionnaires);
    this.planifierResynchronisation();

    // Late joiner: report the state it is arriving into, so an indicator
    // mounted mid-connection does not sit on "fermé" until the next change.
    if (this.source !== null) {
      gestionnaires.onEtat?.(this.source.readyState === EventSource.OPEN ? "ouvert" : "connexion");
    }

    return () => {
      const restants = this.abonnes.get(canal);
      if (!restants) return;
      restants.delete(gestionnaires);
      if (restants.size === 0) {
        this.abonnes.delete(canal);
      }
      this.planifierResynchronisation();
    };
  }

  /**
   * Reopens only when the set of channels actually changed. Subscribing to a
   * channel already covered by the open connection costs nothing.
   */
  private planifierResynchronisation(): void {
    if (this.minuteurRegroupement !== null) {
      clearTimeout(this.minuteurRegroupement);
    }
    this.minuteurRegroupement = setTimeout(() => {
      this.minuteurRegroupement = null;
      const url = construireUrl(this.abonnes.keys());
      if (url === this.urlCourante) {
        return;
      }
      this.urlCourante = url;
      this.annulerReconnexion();
      this.delaiReconnexion = DELAI_INITIAL_MS;
      if (url === null) {
        this.fermer();
        this.signalerEtat("ferme");
      } else {
        // A channel-set change, so hand over rather than cut: the channels
        // that did not change must not miss anything.
        this.ouvrir(true);
      }
    }, DELAI_REGROUPEMENT_MS);
  }

  private signalerEtat(etat: EtatFluxSse): void {
    for (const pourCeCanal of this.abonnes.values()) {
      for (const gestionnaires of pourCeCanal) {
        gestionnaires.onEtat?.(etat);
      }
    }
  }

  private fermer(): void {
    this.fermerSortante();
    if (this.source !== null) {
      this.source.close();
      this.source = null;
    }
  }

  private fermerSortante(): void {
    if (this.sortante !== null) {
      this.sortante.close();
      this.sortante = null;
    }
  }

  private annulerReconnexion(): void {
    if (this.minuteurReconnexion !== null) {
      clearTimeout(this.minuteurReconnexion);
      this.minuteurReconnexion = null;
    }
  }

  private planifierReconnexion(): void {
    this.annulerReconnexion();
    // Full jitter: a random delay in [0, delaiReconnexion], so many tabs
    // reconnecting after a shared outage do not all hit the server at once.
    const delai = Math.random() * this.delaiReconnexion;
    this.delaiReconnexion = Math.min(this.delaiReconnexion * 2, DELAI_MAX_MS);
    this.minuteurReconnexion = setTimeout(() => this.ouvrir(), delai);
  }

  /**
   * @param passageDeRelais keep the live connection delivering until the new
   *   one is open. Used when the channel set changed; never used for a
   *   reconnect after an error, where the old socket is already gone.
   */
  private ouvrir(passageDeRelais = false): void {
    const url = this.urlCourante;
    if (url === null) {
      return;
    }

    // At most one connection ever lingers: a second change while a handover is
    // still in flight drops the older one rather than stacking sockets, which
    // would spend the very per-origin budget this module exists to protect.
    this.fermerSortante();
    if (passageDeRelais && this.source !== null && this.source.readyState === EventSource.OPEN) {
      this.sortante = this.source;
      this.source = null;
    } else {
      this.fermer();
    }
    this.signalerEtat("connexion");

    // withCredentials: the subscriber cookie is what binds this connection's
    // `abonne:` channel, and an EventSource cannot set a header to carry it
    // instead. Harmless for a page with no cookie -- it simply sends none, and
    // the server opens the ligne/gare channels alone. The API already answers
    // with an explicit origin and Access-Control-Allow-Credentials, which this
    // requires (a wildcard origin would be refused, and is rejected at startup).
    const nouvelleSource = new EventSource(url, { withCredentials: true });
    this.source = nouvelleSource;

    nouvelleSource.onopen = () => {
      this.delaiReconnexion = DELAI_INITIAL_MS;
      // The replacement is live; the old one has nothing left to deliver.
      this.fermerSortante();
      this.signalerEtat("ouvert");
    };

    nouvelleSource.onerror = () => {
      // The browser already retries a CONNECTING EventSource on its own, but we
      // take over explicitly so the backoff curve is ours (~1s doubling to a
      // 30s cap with jitter) rather than the UA default.
      if (this.source !== nouvelleSource) {
        return;
      }
      this.signalerEtat("connexion");
      this.fermer();
      this.planifierReconnexion();
    };

    this.ecouter(nouvelleSource, "position");
    this.ecouter(nouvelleSource, "statut");
    this.ecouter(nouvelleSource, "retard");
    this.ecouter(nouvelleSource, "incident");
    this.ecouter(nouvelleSource, "notification");

    this.installerEcouteVisibilite();
  }

  /**
   * Routes one event name. Every frame is an envelope carrying the channel it
   * belongs to, and only the handlers registered for that channel see it --
   * which is what keeps invariant 5 true on the client too: a component
   * watching one gare is never handed the rest of the network, even though the
   * bytes now share a socket with it.
   */
  private ecouter(source: EventSource, nom: NomEvenement): void {
    source.addEventListener(nom, (evenement) => {
      const message = evenement as MessageEvent<string>;
      let enveloppe: EnveloppeSse<unknown>;
      try {
        enveloppe = JSON.parse(message.data) as EnveloppeSse<unknown>;
      } catch {
        // A malformed delta must not take the whole stream down.
        return;
      }
      if (!Array.isArray(enveloppe.canaux)) {
        return;
      }
      for (const canal of enveloppe.canaux) {
        const pourCeCanal = this.abonnes.get(canal);
        if (!pourCeCanal) {
          continue;
        }
        for (const gestionnaires of pourCeCanal) {
          try {
            this.distribuer(gestionnaires, nom, enveloppe.donnees);
          } catch {
            // One consumer throwing must not stop the others from being served.
          }
        }
      }
    });
  }

  private distribuer(
    gestionnaires: GestionnairesFluxSse,
    nom: NomEvenement,
    donnees: unknown,
  ): void {
    switch (nom) {
      case "position":
        gestionnaires.onPosition?.(donnees as EvenementPosition);
        return;
      case "statut":
        gestionnaires.onStatut?.(donnees as EvenementStatut);
        return;
      case "retard":
        gestionnaires.onRetard?.(donnees as EvenementRetard);
        return;
      case "incident":
        gestionnaires.onIncident?.(donnees as EvenementIncident);
        return;
      case "notification":
        gestionnaires.onNotification?.(donnees as EvenementNotification);
    }
  }

  private installerEcouteVisibilite(): void {
    if (this.ecouteVisibilite || typeof document === "undefined") {
      return;
    }
    this.ecouteVisibilite = true;
    document.addEventListener("visibilitychange", () => {
      if (document.visibilityState !== "visible" || this.urlCourante === null) {
        return;
      }
      // A frozen/dropped background tab may leave the source in any state; if
      // it is not OPEN, reconnect immediately and reset the backoff instead of
      // waiting out whatever delay was in flight.
      if (this.source === null || this.source.readyState !== EventSource.OPEN) {
        this.annulerReconnexion();
        this.delaiReconnexion = DELAI_INITIAL_MS;
        this.ouvrir();
      }
    });
  }
}

const connexion = new ConnexionPartagee();

/**
 * Subscribes to `canal` (`"ligne:3"` or `"gare:1"`) for as long as the calling
 * component is mounted, and applies deltas via `gestionnaires`.
 *
 * `canal === null` subscribes to nothing.
 *
 * Handlers are held in a mutable box rather than captured: a caller passing a
 * fresh handler object every render (common with inline arrow functions) must
 * not cause a resubscribe. Only a change in `canal` itself does that.
 */
export function useFluxSse(canal: string | null, gestionnaires: GestionnairesFluxSse): void {
  // One stable object per mount, whose fields are kept current. The shared
  // connection holds this reference, so updating the fields is enough for it
  // to call the latest handlers.
  const [boite] = useState<GestionnairesFluxSse>(() => ({}));

  // In an effect, not during render: a concurrent render that React discards
  // must not leave this box pointing at handlers from a render that never
  // committed. The connection only reads it on incoming events, which always
  // happen after commit.
  useEffect(() => {
    boite.onPosition = gestionnaires.onPosition;
    boite.onStatut = gestionnaires.onStatut;
    boite.onRetard = gestionnaires.onRetard;
    boite.onIncident = gestionnaires.onIncident;
    boite.onNotification = gestionnaires.onNotification;
    boite.onEtat = gestionnaires.onEtat;
  });

  useEffect(() => {
    if (canal === null) {
      return;
    }
    return connexion.abonner(canal, boite);
  }, [canal, boite]);
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
