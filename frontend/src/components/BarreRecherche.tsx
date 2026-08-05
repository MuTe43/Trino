"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import type { FocusEvent, KeyboardEvent } from "react";
import { useRouter } from "next/navigation";
import { ApiError, rechercherCourses } from "@/lib/api";
import { formaterHeure } from "@/lib/temps";
import type { CourseResumeDTO } from "@/lib/types";

const DELAI_DEBOUNCE_MS = 300;

interface GroupeResultats {
  cle: string;
  etiquette: string;
  resultats: CourseResumeDTO[];
}

/**
 * The backend's /recherche (CourseRepository.rechercher) returns a flat page
 * of CourseResumeDTO matched by `lower(...) like` against the train number,
 * the train name, the ligne name, or any gare the course calls at -- it
 * carries no "which field matched" signal on the wire. Grouping is therefore
 * a client-side heuristic re-checking the same fields in the same order, only
 * so the list reads as organised rather than a flat dump.
 */
function grouper(q: string, resultats: CourseResumeDTO[]): GroupeResultats[] {
  const aiguille = q.trim().toLowerCase();
  const trains: CourseResumeDTO[] = [];
  const lignes: CourseResumeDTO[] = [];
  const gares: CourseResumeDTO[] = [];

  for (const course of resultats) {
    if (
      course.numeroTrain.toLowerCase().includes(aiguille) ||
      course.nomTrain.toLowerCase().includes(aiguille)
    ) {
      trains.push(course);
    } else if (course.ligne.nom.toLowerCase().includes(aiguille)) {
      lignes.push(course);
    } else {
      gares.push(course);
    }
  }

  // Every row is a course, whatever the group. The labels therefore name what
  // matched, not what the rows are -- "Lignes" over a list of trains reads as
  // a list of lignes and sends the passenger looking for something else.
  return [
    { cle: "trains", etiquette: "Trains", resultats: trains },
    { cle: "lignes", etiquette: "Trains sur cette ligne", resultats: lignes },
    { cle: "gares", etiquette: "Trains desservant cette gare", resultats: gares },
  ].filter((groupe) => groupe.resultats.length > 0);
}

export interface BarreRechercheProps {
  /** Overrides the default navigation to `/trains/{id}` if the caller wants
   * to control what a selection does (e.g. centre the map instead). */
  onSelectionner?: (course: CourseResumeDTO) => void;
  className?: string;
}

/**
 * Unified search over train number, train name, ligne and gare/destination.
 * Debounced, keyboard-complete (arrows/Enter/Escape), announces its result
 * count for screen reader users.
 */
export function BarreRecherche({ onSelectionner, className }: BarreRechercheProps) {
  const router = useRouter();
  const [texte, setTexte] = useState("");
  const [resultats, setResultats] = useState<CourseResumeDTO[] | null>(null);
  const [enChargement, setEnChargement] = useState(false);
  const [erreur, setErreur] = useState<string | null>(null);
  const [indexActif, setIndexActif] = useState(-1);
  const [ouvert, setOuvert] = useState(false);
  const requeteRef = useRef(0);
  const inputRef = useRef<HTMLInputElement | null>(null);

  useEffect(() => {
    const q = texte.trim();
    if (q.length === 0) {
      requeteRef.current += 1;
      setResultats(null);
      setErreur(null);
      setEnChargement(false);
      return;
    }

    const idRequete = ++requeteRef.current;
    const minuteur = setTimeout(() => {
      setEnChargement(true);
      rechercherCourses(q, { taille: 20 })
        .then((page) => {
          if (requeteRef.current !== idRequete) return;
          setResultats(page.contenu);
          setErreur(null);
        })
        .catch((e: unknown) => {
          if (requeteRef.current !== idRequete) return;
          setResultats([]);
          setErreur(e instanceof ApiError ? e.message : "La recherche a échoué. Réessayez.");
        })
        .finally(() => {
          if (requeteRef.current === idRequete) setEnChargement(false);
        });
    }, DELAI_DEBOUNCE_MS);

    return () => clearTimeout(minuteur);
  }, [texte]);

  const groupes = useMemo(() => (resultats ? grouper(texte, resultats) : []), [texte, resultats]);
  const plats = useMemo(() => groupes.flatMap((groupe) => groupe.resultats), [groupes]);

  useEffect(() => {
    setIndexActif(plats.length > 0 ? 0 : -1);
  }, [plats]);

  function choisir(course: CourseResumeDTO) {
    setOuvert(false);
    if (onSelectionner) {
      onSelectionner(course);
    } else {
      router.push(`/trains/${course.id}`);
    }
  }

  function surTouche(e: KeyboardEvent<HTMLInputElement>) {
    if (e.key === "ArrowDown") {
      e.preventDefault();
      setOuvert(true);
      setIndexActif((i) => (plats.length === 0 ? -1 : Math.min(plats.length - 1, i + 1)));
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      setIndexActif((i) => (plats.length === 0 ? -1 : Math.max(0, i - 1)));
    } else if (e.key === "Enter") {
      if (indexActif >= 0 && plats[indexActif]) {
        e.preventDefault();
        choisir(plats[indexActif]);
      }
    } else if (e.key === "Escape") {
      if (texte.length > 0) {
        setTexte("");
        setResultats(null);
      } else {
        inputRef.current?.blur();
      }
      setOuvert(false);
    }
  }

  function surPerteFocus(e: FocusEvent<HTMLDivElement>) {
    if (!e.currentTarget.contains(e.relatedTarget as Node | null)) {
      setOuvert(false);
    }
  }

  const idListe = "recherche-resultats";
  const idOption = (i: number) => `recherche-option-${i}`;
  const aOuvert = ouvert && texte.trim().length > 0;
  const annonce =
    texte.trim().length === 0
      ? ""
      : enChargement
        ? "Recherche en cours"
        : plats.length === 0
          ? "Aucun résultat"
          : `${plats.length} résultat${plats.length > 1 ? "s" : ""}`;

  return (
    <div className={["relative font-ui", className ?? ""].join(" ")} onBlur={surPerteFocus}>
      <label htmlFor="recherche-entree" className="sr-only">
        Rechercher un train, une ligne ou une gare
      </label>
      <input
        id="recherche-entree"
        ref={inputRef}
        type="text"
        role="combobox"
        aria-expanded={aOuvert}
        aria-controls={idListe}
        aria-activedescendant={indexActif >= 0 ? idOption(indexActif) : undefined}
        aria-autocomplete="list"
        autoComplete="off"
        value={texte}
        onChange={(e) => {
          setTexte(e.target.value);
          setOuvert(true);
        }}
        onFocus={() => setOuvert(true)}
        onKeyDown={surTouche}
        placeholder="Numéro de train (ex. TN103), gare ou ligne…"
        className="w-full rounded-controle border border-filet bg-papier px-3 py-2 text-sm text-encre placeholder:text-ardoise-400 focus:outline-none"
      />

      <div aria-live="polite" className="sr-only">
        {annonce}
      </div>

      {aOuvert && (
        <div
          id={idListe}
          role="listbox"
          aria-label="Résultats de recherche"
          className="absolute z-10 mt-1 max-h-80 w-full overflow-y-auto rounded-carte border border-filet bg-papier"
        >
          {enChargement && plats.length === 0 && (
            <p className="px-3 py-2 text-sm text-ardoise-400">Recherche en cours…</p>
          )}

          {!enChargement && erreur && <p className="px-3 py-2 text-sm text-statut-r60">{erreur}</p>}

          {!enChargement && !erreur && resultats !== null && plats.length === 0 && (
            <p className="px-3 py-2 text-sm text-ardoise-400">
              Aucun résultat pour « {texte.trim()} ». Essayez un numéro de train (ex. TN103) ou le
              nom d&apos;une gare.
            </p>
          )}

          {groupes.map((groupe) => (
            <div key={groupe.cle} role="group" aria-label={groupe.etiquette}>
              <p className="px-3 pt-2 pb-1 text-xs text-ardoise-400">{groupe.etiquette}</p>
              {groupe.resultats.map((course) => {
                const i = plats.indexOf(course);
                return (
                  <button
                    key={course.id}
                    id={idOption(i)}
                    role="option"
                    aria-selected={i === indexActif}
                    type="button"
                    onMouseEnter={() => setIndexActif(i)}
                    onClick={() => choisir(course)}
                    className={[
                      "flex w-full items-center justify-between gap-2 px-3 py-2 text-left text-sm",
                      i === indexActif ? "bg-ardoise-200/20" : "",
                    ].join(" ")}
                  >
                    <span className="flex items-center gap-2">
                      <span className="chiffres font-medium">{course.numeroTrain}</span>
                      <span className="font-condensee">{course.nomTrain}</span>
                      {/* A train runs several times a day, so the same numéro and
                          nom can appear more than once. The scheduled departure is
                          what tells two runs apart. */}
                      <span className="chiffres text-xs text-ardoise-400">
                        {formaterHeure(course.departTheorique)}
                      </span>
                    </span>
                    <span className="text-xs text-ardoise-400">{course.ligne.nom}</span>
                  </button>
                );
              })}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
