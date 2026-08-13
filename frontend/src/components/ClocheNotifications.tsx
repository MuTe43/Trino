"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import {
  CANAL_ABONNE,
  EVENEMENT_ABONNEMENTS_MODIFIES,
  listerMesAbonnements,
  listerNotifications,
} from "@/lib/abonnement";
import { useFluxSse } from "@/lib/sse";
import { formaterHeure } from "@/lib/temps";
import type { NotificationDTO } from "@/lib/types";

/**
 * The bell in the public header: an unread count that moves without a refresh,
 * and a panel listing what arrived.
 *
 * It renders nothing at all for a visitor who follows nothing. That is not a
 * loading state — it is the honest one: a bell that can never ring is chrome,
 * and the portal's header is deliberately thin (a wordmark, a search, one link).
 * It appears the moment "Suivre ce train" succeeds, via the window event that
 * button fires.
 *
 * Live arrivals come over the connection the map and the station board already
 * share, on this tab's own `abonne:` channel — no second transport, and no
 * polling.
 */

/**
 * Read state is client-side, and deliberately so: `notification` has no `lu`
 * column and adding one would mean a write per glance from an anonymous
 * visitor, on an endpoint that is public. The id of the newest notification
 * seen is enough — ids are monotonic on an append-only table, so "unread" is
 * "id greater than this", which survives a reload and costs nothing.
 */
const CLE_STOCKAGE = "trino.notifications.dernierVu";

function lireDernierVu(): number {
  if (typeof window === "undefined") return 0;
  const brut = window.localStorage.getItem(CLE_STOCKAGE);
  const valeur = brut === null ? 0 : Number(brut);
  return Number.isFinite(valeur) ? valeur : 0;
}

export function ClocheNotifications() {
  const [abonne, setAbonne] = useState(false);
  const [notifications, setNotifications] = useState<NotificationDTO[]>([]);
  const [ouvert, setOuvert] = useState(false);
  const [dernierVu, setDernierVu] = useState(0);
  const conteneur = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    setDernierVu(lireDernierVu());
  }, []);

  /**
   * Whether this visitor has any subscription at all. Asked before opening a
   * stream: an anonymous visitor with no cookie would otherwise have the server
   * refuse an empty subscription list (400), and the client would sit in a
   * reconnect loop for a bell that has nothing to show.
   */
  const rafraichir = useCallback(async () => {
    try {
      const abonnements = await listerMesAbonnements();
      setAbonne(abonnements.length > 0);
      if (abonnements.length === 0) {
        setNotifications([]);
        return;
      }
      const page = await listerNotifications(0, 20);
      setNotifications(page.contenu);
    } catch {
      // A bell is not worth an error banner on the portal's header. If the API
      // is unreachable the map behind it says so far more visibly.
      setAbonne(false);
    }
  }, []);

  useEffect(() => {
    void rafraichir();
    window.addEventListener(EVENEMENT_ABONNEMENTS_MODIFIES, rafraichir);
    return () => window.removeEventListener(EVENEMENT_ABONNEMENTS_MODIFIES, rafraichir);
  }, [rafraichir]);

  useFluxSse(abonne ? CANAL_ABONNE : null, {
    onNotification: (evenement) => {
      setNotifications((precedentes) => {
        // The handover between two connections can deliver the same frame
        // twice (sse.ts keeps the old one alive until the new one opens). Every
        // other delta is last-write-wins so a duplicate is harmless there; a
        // list is not, so it is guarded here.
        if (precedentes.some((n) => n.id === evenement.notificationId)) {
          return precedentes;
        }
        const arrivee: NotificationDTO = {
          id: evenement.notificationId,
          evenement: evenement.evenement,
          courseId: evenement.courseId,
          canal: "IN_APP",
          sujet: evenement.sujet,
          contenu: evenement.contenu,
          statut: "ENVOYE",
          envoyeAt: evenement.emisAt,
        };
        return [arrivee, ...precedentes].slice(0, 20);
      });
    },
  });

  // Click-outside and Escape close the panel. Without this it stays open over
  // the map while the user pans, which on the portal's only screen is the whole
  // page.
  useEffect(() => {
    if (!ouvert) return;
    const surClic = (evenement: MouseEvent) => {
      if (conteneur.current && !conteneur.current.contains(evenement.target as Node)) {
        setOuvert(false);
      }
    };
    const surTouche = (evenement: KeyboardEvent) => {
      if (evenement.key === "Escape") setOuvert(false);
    };
    document.addEventListener("mousedown", surClic);
    document.addEventListener("keydown", surTouche);
    return () => {
      document.removeEventListener("mousedown", surClic);
      document.removeEventListener("keydown", surTouche);
    };
  }, [ouvert]);

  if (!abonne) {
    return null;
  }

  const nonLues = notifications.filter((notification) => notification.id > dernierVu).length;

  function basculer() {
    const ouverture = !ouvert;
    setOuvert(ouverture);
    if (ouverture && notifications.length > 0) {
      const plusRecent = Math.max(...notifications.map((notification) => notification.id));
      setDernierVu(plusRecent);
      window.localStorage.setItem(CLE_STOCKAGE, String(plusRecent));
    }
  }

  return (
    <div ref={conteneur} className="relative shrink-0">
      <button
        type="button"
        onClick={basculer}
        aria-expanded={ouvert}
        aria-label={
          nonLues > 0 ? `Notifications, ${nonLues} non lues` : "Notifications, aucune non lue"
        }
        className="relative flex h-8 w-8 items-center justify-center rounded-controle text-ardoise-700 hover:bg-papier"
      >
        {/* Inline SVG rather than an icon dependency: one glyph does not justify
            a package, and the stroke follows currentColor. */}
        <svg
          viewBox="0 0 20 20"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.5"
          strokeLinecap="round"
          strokeLinejoin="round"
          className="h-5 w-5"
          aria-hidden="true"
        >
          <path d="M10 3a4.5 4.5 0 0 0-4.5 4.5c0 3-1.2 4.2-1.7 4.7a.5.5 0 0 0 .35.85h11.7a.5.5 0 0 0 .35-.85c-.5-.5-1.7-1.7-1.7-4.7A4.5 4.5 0 0 0 10 3Z" />
          <path d="M8.5 16a1.75 1.75 0 0 0 3 0" />
        </svg>
        {nonLues > 0 ? (
          <span className="chiffres absolute -right-0.5 -top-0.5 flex h-4 min-w-4 items-center justify-center rounded-full bg-statut-r30 px-1 text-[10px] font-medium text-white">
            {nonLues > 9 ? "9+" : nonLues}
          </span>
        ) : null}
      </button>

      {ouvert ? (
        <div className="absolute right-0 top-10 z-50 max-h-96 w-80 overflow-y-auto rounded-carte border border-filet bg-white">
          <p className="border-b border-filet px-3 py-2 text-xs font-medium text-ardoise-700">
            Notifications
          </p>
          {notifications.length === 0 ? (
            <p className="px-3 py-4 text-xs text-ardoise-400">
              Rien pour l&apos;instant. Vous serez prévenu en cas de retard ou d&apos;incident.
            </p>
          ) : (
            <ul>
              {notifications.map((notification) => (
                <li key={notification.id} className="border-b border-filet px-3 py-2 last:border-b-0">
                  <p className="text-xs font-medium text-encre">{notification.sujet}</p>
                  <p className="mt-0.5 text-xs text-ardoise-400">{notification.contenu}</p>
                  {notification.envoyeAt ? (
                    <p className="chiffres mt-0.5 text-[10px] text-ardoise-400">
                      {formaterHeure(notification.envoyeAt)}
                    </p>
                  ) : null}
                </li>
              ))}
            </ul>
          )}
        </div>
      ) : null}
    </div>
  );
}
