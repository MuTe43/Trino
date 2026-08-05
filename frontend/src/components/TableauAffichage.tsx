"use client";

import { useEffect, useReducer, useState } from "react";
import { listerDeparts } from "@/lib/api";
import { useFluxSse } from "@/lib/sse";
import { reducerDeparts, trierDeparts } from "@/lib/departs";
import { libelleStatut, styleStatut } from "@/lib/couleurs";
import type { DepartGareDTO } from "@/lib/types";
import { formaterHeure, formaterHorloge } from "@/lib/temps";

// This screen runs untouched for hours: a REST resync backstops a missed SSE
// delta or a mid-shift API restart, on top of the live channel.
const RAFRAICHISSEMENT_MS = 60_000;
// Rows are sized (in vh) so this many always fit at 1080p without scrolling.
const LIGNES_AFFICHEES = 9;

/**
 * The live clock. Rendered only from `useEffect` so the server-rendered
 * markup and the client's first paint agree on a fixed placeholder -- a
 * clock built from `new Date()` at both SSR time and hydration time would
 * otherwise mismatch by however many milliseconds elapsed in between.
 */
function Horloge() {
  const [maintenant, setMaintenant] = useState<Date | null>(null);

  useEffect(() => {
    const actualiser = () => setMaintenant(new Date());
    actualiser();
    const id = setInterval(actualiser, 1_000);
    return () => clearInterval(id);
  }, []);

  return (
    <span className="chiffres">{maintenant ? formaterHorloge(maintenant) : "--:--:--"}</span>
  );
}

function LigneAffichage({ depart }: { depart: DepartGareDTO }) {
  const annule = depart.statut === "ANNULE";
  // Only the -sombre ramp on this ground -- the light tokens fail contrast.
  const style = styleStatut(depart.statut, depart.classeRetard, "sombre");
  const differe = !annule && depart.departEstime !== depart.departTheorique;
  const heureAffichee = annule ? depart.departTheorique : depart.departEstime;

  return (
    <div className="flex min-h-[10vh] items-center gap-[2vw] border-b border-filet-sombre px-[2vw]">
      <div className="w-[clamp(64px,20vw,220px)] shrink-0 sm:w-[clamp(90px,13vw,180px)]">
        <span className="chiffres block h-[clamp(13px,2.2vh,26px)] font-condensee text-[clamp(11px,2vh,24px)] leading-none text-ardoise-400 line-through">
          {differe ? formaterHeure(depart.departTheorique) : " "}
        </span>
        <span
          className={[
            "chiffres block font-condensee leading-none text-[clamp(22px,5vh,72px)]",
            annule ? "text-ardoise-400" : differe ? style.texte : "text-papier",
          ].join(" ")}
        >
          {formaterHeure(heureAffichee)}
        </span>
      </div>

      <div className="min-w-0 flex-1">
        <p
          className={[
            "truncate font-condensee leading-none text-[clamp(19px,4.5vh,64px)]",
            annule ? "text-ardoise-400 line-through" : "text-papier",
          ].join(" ")}
        >
          {depart.destination}
        </p>
      </div>

      <div className="hidden w-[clamp(56px,9vw,120px)] shrink-0 sm:block">
        <span
          className={[
            "chiffres text-[clamp(12px,2.2vh,30px)]",
            annule ? "text-ardoise-400" : "text-ardoise-200",
          ].join(" ")}
        >
          {depart.numeroTrain}
        </span>
      </div>

      <div className="hidden w-[clamp(40px,6vw,90px)] shrink-0 sm:block">
        <span
          className={[
            "chiffres text-[clamp(12px,2.2vh,30px)]",
            annule ? "text-ardoise-400" : "text-ardoise-200",
          ].join(" ")}
        >
          {depart.quai ?? "—"}
        </span>
      </div>

      <div className="w-[clamp(80px,22vw,170px)] shrink-0 text-right sm:w-[clamp(90px,13vw,170px)] sm:text-left">
        {annule ? (
          <span className="text-[clamp(12px,2.2vh,30px)] text-statut-r60-sombre">
            {libelleStatut("ANNULE")}
          </span>
        ) : (
          <span className={["text-[clamp(12px,2.2vh,30px)]", style.texte].join(" ")}>
            {style.etiquette}
          </span>
        )}
      </div>
    </div>
  );
}

export interface TableauAffichageProps {
  gareId: number;
  gareNom: string;
  departsInitiaux: DepartGareDTO[];
}

/**
 * The station board: read from across a hall, no pointer, no navigation.
 * Sorted by `departEstime` so a delayed train falls down the board. Live via
 * `gare:{gareId}`, backstopped by a 60s REST resync -- both apply silently,
 * never an error banner or a flash of empty state.
 */
export function TableauAffichage({ gareId, gareNom, departsInitiaux }: TableauAffichageProps) {
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
      listerDeparts(gareId, 20)
        .then((departs) => dispatch({ type: "INIT", departs }))
        .catch(() => {
          // Silent: the board keeps its last known-good state rather than
          // blanking on a missed resync.
        });
    }, RAFRAICHISSEMENT_MS);
    return () => clearInterval(id);
  }, [gareId]);

  const departs = trierDeparts(etat).slice(0, LIGNES_AFFICHEES);

  return (
    <div className="flex h-full w-full flex-col">
      <header className="flex shrink-0 items-baseline justify-between border-b border-filet-sombre px-[2vw] py-[2vh]">
        <h1 className="font-condensee leading-none text-[clamp(20px,3.4vh,48px)] text-papier">
          {gareNom}
        </h1>
        <p className="font-condensee leading-none text-[clamp(20px,3.4vh,48px)] text-papier">
          <Horloge />
        </p>
      </header>

      <div className="flex-1">
        {departs.length === 0 ? (
          <p className="px-[2vw] py-[3vh] font-condensee text-[clamp(16px,2.6vh,34px)] text-ardoise-400">
            Aucun départ prévu pour le moment.
          </p>
        ) : (
          departs.map((depart) => <LigneAffichage key={depart.courseId} depart={depart} />)
        )}
      </div>
    </div>
  );
}
