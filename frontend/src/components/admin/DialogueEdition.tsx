"use client";

import { useEffect, useId, useMemo, useRef, useState } from "react";
import { ApiError } from "@/lib/api";

/**
 * Schema-driven edit dialog, shared by every administration screen.
 *
 * The part worth getting right is the server error. In phase 6 a validation key
 * was returned for a field the form did not render: the key was stored, never
 * displayed, and the user got "Le formulaire contient des erreurs." with every
 * field unmarked and nothing to act on. So here, every `details[].champ` is
 * either attached to its field **or** printed in the summary block — a detail is
 * never silently dropped. A 409 CONFLIT carries no details at all, only a
 * message, and that message is the whole point (it says why the write was
 * refused), so it lands in the same block.
 */

/**
 * `multi` was added in phase 8 for the alert rules' channel set. Its value
 * travels as a comma-joined string ("IN_APP,EMAIL") rather than as an array,
 * because {@link ValeursFormulaire} is flat by design — widening it to hold
 * arrays would touch every screen for one field on one of them. The caller
 * splits it on the way out, which is one line and keeps this dialog schema-driven.
 */
export type TypeChamp = "texte" | "nombre" | "selection" | "booleen" | "lecture" | "multi";

export interface ChampFormulaire {
  nom: string;
  libelle: string;
  type: TypeChamp;
  options?: { valeur: string; libelle: string }[];
  obligatoire?: boolean;
  aide?: string;
  /** `step` for a number input — coordinates need decimals, counts do not. */
  pas?: string;
}

export type ValeursFormulaire = Record<string, string | number | boolean | null>;

interface ProprietesDialogueEdition {
  titre: string;
  champs: ChampFormulaire[];
  valeursInitiales: ValeursFormulaire;
  onEnregistrer: (valeurs: ValeursFormulaire) => Promise<void>;
  onFermer: () => void;
  libelleValidation?: string;
}

export default function DialogueEdition({
  titre,
  champs,
  valeursInitiales,
  onEnregistrer,
  onFermer,
  libelleValidation = "Enregistrer",
}: ProprietesDialogueEdition) {
  const [valeurs, setValeurs] = useState<ValeursFormulaire>(valeursInitiales);
  const [erreursChamps, setErreursChamps] = useState<Record<string, string>>({});
  const [messagesGeneraux, setMessagesGeneraux] = useState<string[]>([]);
  const [envoiEnCours, setEnvoiEnCours] = useState(false);
  const idTitre = useId();
  const conteneur = useRef<HTMLDivElement | null>(null);
  const declencheur = useRef<Element | null>(null);

  const nomsChamps = useMemo(() => new Set(champs.map((c) => c.nom)), [champs]);

  // Focus moves into the dialog on open and returns to whatever opened it on
  // close: a keyboard user must not be dropped back at the top of the document.
  useEffect(() => {
    declencheur.current = document.activeElement;
    const premier = conteneur.current?.querySelector<HTMLElement>(
      "input, select, textarea, button",
    );
    premier?.focus();
    return () => {
      if (declencheur.current instanceof HTMLElement) {
        declencheur.current.focus();
      }
    };
  }, []);

  useEffect(() => {
    const surTouche = (evenement: KeyboardEvent) => {
      if (evenement.key === "Escape") onFermer();
    };
    document.addEventListener("keydown", surTouche);
    return () => document.removeEventListener("keydown", surTouche);
  }, [onFermer]);

  function definir(nom: string, valeur: string | number | boolean | null) {
    setValeurs((precedent) => ({ ...precedent, [nom]: valeur }));
  }

  async function soumettre(evenement: React.FormEvent) {
    evenement.preventDefault();
    setEnvoiEnCours(true);
    setErreursChamps({});
    setMessagesGeneraux([]);
    try {
      await onEnregistrer(valeurs);
    } catch (erreur) {
      const parChamp: Record<string, string> = {};
      const generaux: string[] = [];
      if (erreur instanceof ApiError) {
        const details = erreur.erreur?.details ?? [];
        for (const detail of details) {
          if (nomsChamps.has(detail.champ)) {
            parChamp[detail.champ] = detail.probleme;
          } else {
            // Orphan key: no input carries this name. It still has to be
            // readable somewhere, or the user sees "invalide" and no cause.
            generaux.push(`${detail.champ} : ${detail.probleme}`);
          }
        }
        if (details.length === 0) {
          generaux.push(erreur.message);
        }
      } else {
        generaux.push("Une erreur inattendue est survenue.");
      }
      setErreursChamps(parChamp);
      setMessagesGeneraux(generaux);
      setEnvoiEnCours(false);
      return;
    }
    setEnvoiEnCours(false);
  }

  return (
    <div className="fixed inset-0 z-50 flex items-start justify-center overflow-y-auto bg-encre/40 p-4">
      <div
        ref={conteneur}
        role="dialog"
        aria-modal="true"
        aria-labelledby={idTitre}
        className="my-8 w-full max-w-lg rounded-carte border border-filet bg-white"
      >
        <div className="border-b border-filet px-4 py-3">
          <h2 id={idTitre} className="font-condensee text-base font-medium text-encre">
            {titre}
          </h2>
        </div>

        <form onSubmit={soumettre} className="flex flex-col gap-3 px-4 py-4">
          {messagesGeneraux.length > 0 ? (
            <div
              role="alert"
              className="rounded-controle border border-statut-r60 px-3 py-2 text-sm text-statut-r60"
            >
              {messagesGeneraux.map((message) => (
                <p key={message}>{message}</p>
              ))}
            </div>
          ) : null}

          {champs.map((champ) => {
            const valeur = valeurs[champ.nom];
            const erreur = erreursChamps[champ.nom];
            const idChamp = `${idTitre}-${champ.nom}`;
            return (
              <div key={champ.nom} className="flex flex-col gap-1">
                <label htmlFor={idChamp} className="text-xs font-medium text-ardoise-700">
                  {champ.libelle}
                  {champ.obligatoire ? <span aria-hidden="true"> *</span> : null}
                </label>

                {champ.type === "lecture" ? (
                  <p
                    id={idChamp}
                    className="chiffres rounded-controle border border-filet bg-papier px-2 py-1.5 text-sm text-ardoise-400"
                  >
                    {valeur === null || valeur === "" ? "—" : String(valeur)}
                  </p>
                ) : null}

                {champ.type === "texte" ? (
                  <input
                    id={idChamp}
                    type="text"
                    value={valeur === null || valeur === undefined ? "" : String(valeur)}
                    onChange={(e) => definir(champ.nom, e.target.value)}
                    aria-invalid={erreur ? true : undefined}
                    // `required`, not just the asterisk: the star is decoration,
                    // and a blank required field otherwise reaches the server.
                    required={champ.obligatoire}
                    className="rounded-controle border border-filet px-2 py-1.5 text-sm"
                  />
                ) : null}

                {champ.type === "nombre" ? (
                  <input
                    id={idChamp}
                    type="number"
                    step={champ.pas ?? "1"}
                    value={valeur === null || valeur === undefined ? "" : String(valeur)}
                    onChange={(e) =>
                      definir(champ.nom, e.target.value === "" ? null : Number(e.target.value))
                    }
                    aria-invalid={erreur ? true : undefined}
                    required={champ.obligatoire}
                    className="chiffres rounded-controle border border-filet px-2 py-1.5 text-sm"
                  />
                ) : null}

                {champ.type === "selection" ? (
                  <select
                    id={idChamp}
                    value={valeur === null || valeur === undefined ? "" : String(valeur)}
                    onChange={(e) => definir(champ.nom, e.target.value === "" ? null : e.target.value)}
                    aria-invalid={erreur ? true : undefined}
                    className="rounded-controle border border-filet px-2 py-1.5 text-sm"
                  >
                    {(champ.options ?? []).map((option) => (
                      <option key={option.valeur} value={option.valeur}>
                        {option.libelle}
                      </option>
                    ))}
                  </select>
                ) : null}

                {champ.type === "booleen" ? (
                  <input
                    id={idChamp}
                    type="checkbox"
                    checked={Boolean(valeur)}
                    onChange={(e) => definir(champ.nom, e.target.checked)}
                    className="h-4 w-4 self-start"
                  />
                ) : null}

                {champ.type === "multi" ? (
                  <div id={idChamp} className="flex flex-wrap gap-3">
                    {(champ.options ?? []).map((option) => {
                      const selection = String(valeur ?? "")
                        .split(",")
                        .filter((v) => v !== "");
                      const coche = selection.includes(option.valeur);
                      return (
                        <label
                          key={option.valeur}
                          className="flex items-center gap-1.5 text-sm text-encre"
                        >
                          <input
                            type="checkbox"
                            checked={coche}
                            onChange={(e) =>
                              definir(
                                champ.nom,
                                (e.target.checked
                                  ? [...selection, option.valeur]
                                  : selection.filter((v) => v !== option.valeur)
                                ).join(","),
                              )
                            }
                            className="h-4 w-4"
                          />
                          {option.libelle}
                        </label>
                      );
                    })}
                  </div>
                ) : null}

                {champ.aide ? <p className="text-xs text-ardoise-400">{champ.aide}</p> : null}
                {erreur ? <p className="text-xs text-statut-r60">{erreur}</p> : null}
              </div>
            );
          })}

          <div className="mt-2 flex justify-end gap-2">
            <button
              type="button"
              onClick={onFermer}
              className="rounded-controle border border-filet px-3 py-1.5 text-sm"
            >
              Annuler
            </button>
            <button
              type="submit"
              disabled={envoiEnCours}
              className="rounded-controle bg-sncft-bleu px-3 py-1.5 text-sm font-medium text-papier disabled:opacity-50"
            >
              {envoiEnCours ? "Enregistrement…" : libelleValidation}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
