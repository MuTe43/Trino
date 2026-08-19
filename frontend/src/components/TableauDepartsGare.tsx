"use client";

import { useEffect, useReducer } from "react";
import Link from "next/link";
import { listerDeparts } from "@/lib/api";
import { useFluxSse } from "@/lib/sse";
import { reducerDeparts, trierDeparts } from "@/lib/departs";
import { styleStatut } from "@/lib/couleurs";
import type { DepartGareDTO } from "@/lib/types";
import { formaterHeure } from "@/lib/temps";

// Same interval as the kiosk board. A delta is lost whenever the EventSource is
// reconnecting -- frames carry no id, so there is no replay (see HubSse) -- and
// without a resync this table stayed wrong until the visitor navigated away and
// back. The kiosk has had this backstop since phase 4; the public page did not,
// which meant the screen in the hall and the phone in the visitor's hand could
// disagree about the same train.
const RAFRAICHISSEMENT_MS = 60_000;

export interface TableauDepartsGareProps {
  gareId: number;
  departsInitiaux: DepartGareDTO[];
}

/**
 * The public station page's departure table: dense, hairline-separated, the
 * light status tokens. Live via `gare:{gareId}` -- a `statut`/`retard` delta
 * patches the one row it names -- and backstopped by a 60s REST resync for the
 * deltas that arrive while the stream is reconnecting.
 */
export function TableauDepartsGare({ gareId, departsInitiaux }: TableauDepartsGareProps) {
  const [etat, dispatch] = useReducer(
    reducerDeparts,
    departsInitiaux,
    (initiaux) => reducerDeparts(new Map(), { type: "INIT", departs: initiaux }),
  );

  useFluxSse(`gare:${gareId}`, {
    onStatut: (evenement) => dispatch({ type: "STATUT", gareId, evenement }),
    onRetard: (evenement) => dispatch({ type: "RETARD", gareId, evenement }),
  });

  useEffect(() => {
    const id = setInterval(() => {
      listerDeparts(gareId)
        .then((departs) => dispatch({ type: "INIT", departs }))
        .catch(() => {
          // Silent, like the kiosk: the table keeps its last known-good rows
          // rather than emptying itself because one poll did not come back.
        });
    }, RAFRAICHISSEMENT_MS);
    return () => clearInterval(id);
  }, [gareId]);

  const departs = trierDeparts(etat);

  if (departs.length === 0) {
    return <p className="mt-6 text-sm text-ardoise-400">Aucun départ prévu pour le moment.</p>;
  }

  return (
    <table className="mt-6 w-full border-collapse text-sm">
      <thead>
        <tr className="border-b border-filet text-left text-xs text-ardoise-400">
          <th scope="col" className="py-2 pr-3 font-normal">
            Départ
          </th>
          <th scope="col" className="py-2 pr-3 font-normal">
            Destination
          </th>
          <th scope="col" className="hidden py-2 pr-3 font-normal sm:table-cell">
            Train
          </th>
          <th scope="col" className="hidden py-2 pr-3 font-normal sm:table-cell">
            Voie
          </th>
          <th scope="col" className="py-2 font-normal">
            Statut
          </th>
        </tr>
      </thead>
      <tbody>
        {departs.map((depart) => {
          const annule = depart.statut === "ANNULE";
          const style = styleStatut(depart.statut, depart.classeRetard);
          const differe = !annule && depart.departEstime !== depart.departTheorique;

          return (
            <tr key={depart.courseId} className="border-b border-filet">
              <td className="py-2 pr-3">
                <span className="chiffres inline-flex items-baseline gap-1.5 whitespace-nowrap">
                  {differe && (
                    <span className="text-ardoise-400 line-through">
                      {formaterHeure(depart.departTheorique)}
                    </span>
                  )}
                  <span className={differe ? style.texte : "text-encre"}>
                    {formaterHeure(annule ? depart.departTheorique : depart.departEstime)}
                  </span>
                </span>
              </td>
              <td
                className={[
                  "py-2 pr-3 font-condensee",
                  annule ? "text-ardoise-400 line-through" : "text-encre",
                ].join(" ")}
              >
                <Link href={`/trains/${depart.courseId}`} className="hover:underline">
                  {depart.destination}
                </Link>
              </td>
              <td className="chiffres hidden py-2 pr-3 text-ardoise-700 sm:table-cell">
                {depart.numeroTrain}
              </td>
              <td className="chiffres hidden py-2 pr-3 text-ardoise-700 sm:table-cell">
                {depart.quai ?? "—"}
              </td>
              <td className="py-2">
                <span className={["text-xs", style.texte].join(" ")}>{style.etiquette}</span>
              </td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}
