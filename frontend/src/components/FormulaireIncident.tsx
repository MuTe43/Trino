"use client";

import { useEffect, useState } from "react";
import type { FormEvent } from "react";
import { ApiError, chargerToutesPages, listerGares, listerLignes } from "@/lib/api";
import { declarerIncident } from "@/lib/incidents";
import { libelleCauseRetard, libelleGravite, libelleTypeIncident } from "@/lib/couleurs";
import { maintenantPourInputLocalTunis, versIsoDepuisLocalTunis } from "@/lib/temps";
import { BarreRecherche } from "./BarreRecherche";
import type {
  CauseRetard,
  CorpsIncident,
  CourseResumeDTO,
  Gare,
  Gravite,
  IncidentDTO,
  LigneDTO,
  TypeIncident,
} from "@/lib/types";

const TYPES_INCIDENT: TypeIncident[] = [
  "PANNE_LOCOMOTIVE",
  "DEFAUT_SIGNALISATION",
  "ACCIDENT",
  "OBSTACLE_VOIE",
  "INTEMPERIES",
  "COUPURE_ELECTRIQUE",
  "TRAVAUX",
  "AUTRE",
];

const GRAVITES: Gravite[] = ["MINEURE", "MOYENNE", "MAJEURE", "CRITIQUE"];

const ACTIONS_COURSE = ["ARRET_EXCEPTIONNEL", "ANNULE"] as const;
type ActionCourse = (typeof ACTIONS_COURSE)[number];

const LIBELLES_ACTION_COURSE: Record<ActionCourse, string> = {
  ARRET_EXCEPTIONNEL: "Arrêt exceptionnel (signal perdu)",
  ANNULE: "Course supprimée",
};

const CAUSES_RETARD: CauseRetard[] = [
  "INCIDENT_TECHNIQUE",
  "METEO",
  "ACCIDENT",
  "SIGNALISATION",
  "TRAVAUX",
  "ATTENTE_CORRESPONDANCE",
  "AFFLUENCE_VOYAGEURS",
  "AUTRE",
];

const CHAMP =
  "w-full rounded-controle border border-filet bg-white p-2 text-sm text-encre " +
  "placeholder:text-ardoise-200 focus:border-sncft-bleu disabled:bg-papier disabled:text-ardoise-400";
const ETIQUETTE = "mb-1 block text-xs font-medium text-ardoise-400";
const ERREUR_CHAMP = "mt-1 text-xs text-statut-r60";

/** Server-side `@AssertTrue` cross-field checks report by method name (minus
 * `is`), not by the record field they concern. Kept literal here (not a runtime
 * lookup) so every possible key visibly has a home, per invariant 8's spirit. */
type ChampErreur =
  | "type"
  | "description"
  | "survenuAt"
  | "gravite"
  | "impact"
  | "localisationRenseignee"
  | "actionCourseRattachee"
  | "actionCourseAutorisee"
  | "causeRattachee";

/**
 * Checks whose method name does not match the input they concern, folded onto
 * the field the user has to fix.
 *
 * `survenuAtPasseOuPresent` fires on a mistyped year -- a `datetime-local` has
 * no `max`, so native validation lets it through. Without this alias the key was
 * stored and never rendered: the agent got "Le formulaire contient des erreurs."
 * with every field unmarked and no way to find out which one.
 */
const ALIAS_CHAMP: Record<string, ChampErreur> = {
  survenuAtPasseOuPresent: "survenuAt",
};

export interface FormulaireIncidentProps {
  onCree: (incident: IncidentDTO) => void;
  onAnnuler?: () => void;
}

/**
 * Incident declaration -- the only non-trivial form in the app. Plain
 * controlled inputs, native `required`, and the 400 envelope's `details[]`
 * mapped field by field onto the matching input (invariant 9's twin: a
 * malformed body on a route you may not touch gets 403 first, but a
 * malformed body on a route you may touch gets exactly these messages).
 */
export function FormulaireIncident({ onCree, onAnnuler }: FormulaireIncidentProps) {
  const [type, setType] = useState<TypeIncident>("PANNE_LOCOMOTIVE");
  const [description, setDescription] = useState("");
  const [survenuAt, setSurvenuAt] = useState(() => maintenantPourInputLocalTunis());
  const [gravite, setGravite] = useState<Gravite>("MINEURE");
  const [impact, setImpact] = useState("");
  const [gareId, setGareId] = useState<number | "">("");
  const [ligneId, setLigneId] = useState<number | "">("");
  const [courseSelectionnee, setCourseSelectionnee] = useState<CourseResumeDTO | null>(null);
  const [actionCourse, setActionCourse] = useState<ActionCourse | "">("");
  const [causeRetard, setCauseRetard] = useState<CauseRetard | "">("");

  const [gares, setGares] = useState<Gare[]>([]);
  const [lignes, setLignes] = useState<LigneDTO[]>([]);
  const [enCours, setEnCours] = useState(false);
  const [erreurGenerale, setErreurGenerale] = useState<string | null>(null);
  const [erreursChamps, setErreursChamps] = useState<Partial<Record<ChampErreur, string>>>({});

  useEffect(() => {
    let annule = false;
    Promise.all([
      chargerToutesPages<Gare>((p, t) => listerGares(p, t)),
      chargerToutesPages<LigneDTO>((p, t) => listerLignes(p, t)),
    ]).then(([garesChargees, lignesChargees]) => {
      if (annule) return;
      setGares(garesChargees);
      setLignes(lignesChargees);
    });
    return () => {
      annule = true;
    };
  }, []);

  const localisationRenseignee = gareId !== "" || ligneId !== "" || courseSelectionnee !== null;

  async function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setErreurGenerale(null);

    if (!localisationRenseignee) {
      setErreursChamps({
        localisationRenseignee: "au moins une localisation est requise : gare, ligne ou course",
      });
      return;
    }
    setErreursChamps({});

    const corps: CorpsIncident = {
      type,
      description,
      survenuAt: versIsoDepuisLocalTunis(survenuAt),
      gravite,
      impact,
      ...(gareId !== "" ? { gareId } : {}),
      ...(ligneId !== "" ? { ligneId } : {}),
      ...(courseSelectionnee ? { courseId: courseSelectionnee.id } : {}),
      ...(actionCourse !== "" ? { actionCourse } : {}),
      ...(causeRetard !== "" ? { causeRetard } : {}),
    };

    setEnCours(true);
    try {
      const incident = await declarerIncident(corps);
      onCree(incident);
    } catch (err) {
      if (err instanceof ApiError && err.statut === 400 && err.erreur?.details) {
        const champs: Partial<Record<ChampErreur, string>> = {};
        for (const detail of err.erreur.details) {
          champs[ALIAS_CHAMP[detail.champ] ?? (detail.champ as ChampErreur)] = detail.probleme;
        }
        setErreursChamps(champs);
        setErreurGenerale("Le formulaire contient des erreurs.");
      } else if (err instanceof ApiError) {
        setErreurGenerale(err.message);
      } else {
        setErreurGenerale("La déclaration a échoué. Réessayez.");
      }
    } finally {
      setEnCours(false);
    }
  }

  return (
    <form onSubmit={onSubmit} noValidate={false} className="grid gap-4 sm:grid-cols-2">
      <div>
        <label htmlFor="type" className={ETIQUETTE}>
          Type d&apos;incident
        </label>
        <select
          id="type"
          required
          value={type}
          onChange={(e) => setType(e.target.value as TypeIncident)}
          className={CHAMP}
        >
          {TYPES_INCIDENT.map((t) => (
            <option key={t} value={t}>
              {libelleTypeIncident(t)}
            </option>
          ))}
        </select>
        {erreursChamps.type && <p className={ERREUR_CHAMP}>{erreursChamps.type}</p>}
      </div>

      <div>
        <label htmlFor="gravite" className={ETIQUETTE}>
          Gravité
        </label>
        <select
          id="gravite"
          required
          value={gravite}
          onChange={(e) => setGravite(e.target.value as Gravite)}
          className={CHAMP}
        >
          {GRAVITES.map((g) => (
            <option key={g} value={g}>
              {libelleGravite(g)}
            </option>
          ))}
        </select>
        {erreursChamps.gravite && <p className={ERREUR_CHAMP}>{erreursChamps.gravite}</p>}
      </div>

      <div>
        <label htmlFor="survenuAt" className={ETIQUETTE}>
          Survenu le
        </label>
        <input
          id="survenuAt"
          type="datetime-local"
          required
          value={survenuAt}
          onChange={(e) => setSurvenuAt(e.target.value)}
          className={`chiffres ${CHAMP}`}
        />
        {erreursChamps.survenuAt && <p className={ERREUR_CHAMP}>{erreursChamps.survenuAt}</p>}
      </div>

      <div className="sm:col-span-2">
        <label htmlFor="description" className={ETIQUETTE}>
          Description
        </label>
        <textarea
          id="description"
          required
          maxLength={2000}
          rows={2}
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          className={CHAMP}
          placeholder="Ce qui s'est passé, en quelques phrases."
        />
        {erreursChamps.description && <p className={ERREUR_CHAMP}>{erreursChamps.description}</p>}
      </div>

      <div className="sm:col-span-2">
        <label htmlFor="impact" className={ETIQUETTE}>
          Impact sur la circulation
        </label>
        <textarea
          id="impact"
          required
          maxLength={2000}
          rows={2}
          value={impact}
          onChange={(e) => setImpact(e.target.value)}
          className={CHAMP}
          placeholder="Ex. : ralentissement, voie unique, circulation interrompue."
        />
        {erreursChamps.impact && <p className={ERREUR_CHAMP}>{erreursChamps.impact}</p>}
      </div>

      <fieldset className="sm:col-span-2 rounded-controle border border-filet p-3">
        <legend className="px-1 text-xs font-medium text-ardoise-400">
          Localisation — gare, ligne ou course, au moins une
        </legend>

        <div className="grid gap-3 sm:grid-cols-2">
          <div>
            <label htmlFor="gareId" className={ETIQUETTE}>
              Gare
            </label>
            <select
              id="gareId"
              value={gareId}
              onChange={(e) => setGareId(e.target.value === "" ? "" : Number(e.target.value))}
              className={CHAMP}
            >
              <option value="">Aucune</option>
              {gares.map((gare) => (
                <option key={gare.id} value={gare.id}>
                  {gare.nom}
                </option>
              ))}
            </select>
          </div>

          <div>
            <label htmlFor="ligneId" className={ETIQUETTE}>
              Ligne
            </label>
            <select
              id="ligneId"
              value={ligneId}
              onChange={(e) => setLigneId(e.target.value === "" ? "" : Number(e.target.value))}
              className={CHAMP}
            >
              <option value="">Aucune</option>
              {lignes.map((ligne) => (
                <option key={ligne.id} value={ligne.id}>
                  {ligne.nom}
                </option>
              ))}
            </select>
          </div>
        </div>

        <div className="mt-3">
          <p className={ETIQUETTE}>Course concernée</p>
          {courseSelectionnee ? (
            <div className="flex items-center justify-between gap-2 rounded-controle border border-filet bg-papier px-3 py-2 text-sm">
              <span>
                <span className="chiffres font-medium">{courseSelectionnee.numeroTrain}</span>
                <span className="ml-2 font-condensee">{courseSelectionnee.nomTrain}</span>
              </span>
              <button
                type="button"
                onClick={() => {
                  setCourseSelectionnee(null);
                  setActionCourse("");
                  setCauseRetard("");
                }}
                className="text-xs text-ardoise-400 underline decoration-filet underline-offset-2 hover:text-ardoise-700"
              >
                Retirer
              </button>
            </div>
          ) : (
            <BarreRecherche onSelectionner={setCourseSelectionnee} />
          )}
        </div>

        {erreursChamps.localisationRenseignee && (
          <p className={ERREUR_CHAMP}>{erreursChamps.localisationRenseignee}</p>
        )}
      </fieldset>

      <div>
        <label htmlFor="actionCourse" className={ETIQUETTE}>
          Action sur la course
        </label>
        <select
          id="actionCourse"
          value={actionCourse}
          disabled={!courseSelectionnee}
          onChange={(e) => setActionCourse(e.target.value as ActionCourse | "")}
          className={CHAMP}
        >
          <option value="">Aucune</option>
          {ACTIONS_COURSE.map((a) => (
            <option key={a} value={a}>
              {LIBELLES_ACTION_COURSE[a]}
            </option>
          ))}
        </select>
        {!courseSelectionnee && (
          <p className="mt-1 text-xs text-ardoise-200">Choisissez une course pour l&apos;activer.</p>
        )}
        {(erreursChamps.actionCourseRattachee || erreursChamps.actionCourseAutorisee) && (
          <p className={ERREUR_CHAMP}>
            {erreursChamps.actionCourseRattachee ?? erreursChamps.actionCourseAutorisee}
          </p>
        )}
      </div>

      <div>
        <label htmlFor="causeRetard" className={ETIQUETTE}>
          Cause de retard
        </label>
        <select
          id="causeRetard"
          value={causeRetard}
          disabled={!courseSelectionnee}
          onChange={(e) => setCauseRetard(e.target.value as CauseRetard | "")}
          className={CHAMP}
        >
          <option value="">Suggérée automatiquement</option>
          {CAUSES_RETARD.map((c) => (
            <option key={c} value={c}>
              {libelleCauseRetard(c)}
            </option>
          ))}
        </select>
        {!courseSelectionnee && (
          <p className="mt-1 text-xs text-ardoise-200">
            Sans course, la cause suggérée par le type ne s&apos;applique nulle part.
          </p>
        )}
        {erreursChamps.causeRattachee && <p className={ERREUR_CHAMP}>{erreursChamps.causeRattachee}</p>}
      </div>

      {erreurGenerale && (
        <p role="alert" className="sm:col-span-2 rounded-controle border-l-2 border-statut-r60 bg-white p-3 text-sm text-statut-r60">
          {erreurGenerale}
        </p>
      )}

      <div className="flex items-center gap-3 sm:col-span-2">
        <button
          type="submit"
          disabled={enCours}
          className="rounded-controle bg-sncft-bleu px-4 py-2 text-sm font-medium text-white disabled:opacity-60"
        >
          {enCours ? "Déclaration…" : "Déclarer l'incident"}
        </button>
        {onAnnuler && (
          <button
            type="button"
            onClick={onAnnuler}
            className="rounded-controle border border-filet px-4 py-2 text-sm text-ardoise-700"
          >
            Annuler
          </button>
        )}
      </div>
    </form>
  );
}
