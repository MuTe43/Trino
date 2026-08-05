"use client";

import { useReducer } from "react";
import { useFluxSse } from "@/lib/sse";
import { classeDeRetard, styleStatut } from "@/lib/couleurs";
import { ListeArrets } from "./ListeArrets";
import type {
  CauseRetard,
  CourseResumeDTO,
  EvenementPosition,
  EvenementRetard,
  EvenementStatut,
  PassageDTO,
  SensCourse,
} from "@/lib/types";

const LIBELLES_SENS: Record<SensCourse, string> = {
  ALLER: "Aller",
  RETOUR: "Retour",
};

const LIBELLES_CAUSE: Record<CauseRetard, string> = {
  INCIDENT_TECHNIQUE: "Incident technique",
  METEO: "Conditions météorologiques",
  ACCIDENT: "Accident",
  SIGNALISATION: "Panne de signalisation",
  TRAVAUX: "Travaux sur la voie",
  ATTENTE_CORRESPONDANCE: "Attente d'une correspondance",
  AFFLUENCE_VOYAGEURS: "Affluence de voyageurs",
  AUTRE: "Autre cause",
};

interface Etat {
  course: CourseResumeDTO;
  passages: PassageDTO[];
}

type Action =
  | { type: "STATUT"; evenement: EvenementStatut }
  | { type: "RETARD"; evenement: EvenementRetard }
  | { type: "POSITION"; evenement: EvenementPosition };

/**
 * Applies `ligne:{ligneId}` deltas to one course's header and stop list.
 * Every delta names a `courseId`; a delta for a different course sharing the
 * same ligne channel is dropped rather than mixed in. `retard` carries
 * `passagesRevises` -- applied to the matching `ordre` rows, never triggering
 * a refetch. Never adds `retardMin` to a theoretical time.
 */
function reducer(etat: Etat, action: Action): Etat {
  if (action.evenement.courseId !== etat.course.id) {
    return etat;
  }

  switch (action.type) {
    case "STATUT":
      return {
        ...etat,
        course: {
          ...etat.course,
          statut: action.evenement.statut,
          retardMin: action.evenement.retardMin,
          classeRetard: action.evenement.classeRetard,
          causeRetard: action.evenement.causeRetard,
        },
      };
    case "RETARD": {
      const revisions = new Map(
        action.evenement.passagesRevises.map((revision) => [revision.ordre, revision]),
      );
      return {
        course: {
          ...etat.course,
          retardMin: action.evenement.retardMin,
          classeRetard: action.evenement.classeRetard,
          causeRetard: action.evenement.causeRetard,
        },
        passages: etat.passages.map((passage) => {
          const revision = revisions.get(passage.ordre);
          if (!revision) return passage;
          return {
            ...passage,
            arriveeEstimee: revision.arriveeEstimee,
            departEstime: revision.departEstime,
            retardMin: revision.retardMin,
            // The stop's own bucket, not the course's: a stop that has
            // recovered downstream must not be painted in the colour of the
            // course's worst delay.
            classeRetard: classeDeRetard(revision.retardMin),
          };
        }),
      };
    }
    case "POSITION":
      return {
        ...etat,
        course: {
          ...etat.course,
          position: {
            latitude: action.evenement.latitude,
            longitude: action.evenement.longitude,
            vitesseKmh: action.evenement.vitesseKmh,
          },
          etaSuivante: action.evenement.etaSuivante,
        },
      };
    default:
      return etat;
  }
}

export interface DetailCourseTempsReelProps {
  courseInitiale: CourseResumeDTO;
  passagesInitiaux: PassageDTO[];
}

export function DetailCourseTempsReel({
  courseInitiale,
  passagesInitiaux,
}: DetailCourseTempsReelProps) {
  const [etat, dispatch] = useReducer(reducer, {
    course: courseInitiale,
    passages: passagesInitiaux,
  });

  useFluxSse(`ligne:${courseInitiale.ligne.id}`, {
    onStatut: (evenement) => dispatch({ type: "STATUT", evenement }),
    onRetard: (evenement) => dispatch({ type: "RETARD", evenement }),
    onPosition: (evenement) => dispatch({ type: "POSITION", evenement }),
  });

  const { course, passages } = etat;
  const style = styleStatut(course.statut, course.classeRetard);

  return (
    <div>
      <header className="border-b border-filet pb-4">
        <p className="chiffres text-sm text-ardoise-700">{course.numeroTrain}</p>
        <h1 className="font-condensee text-2xl leading-tight text-encre">{course.nomTrain}</h1>
        <p className="mt-1 text-sm text-ardoise-700">
          {course.ligne.nom} · {LIBELLES_SENS[course.sens]}
        </p>

        <div className="mt-3 flex flex-wrap items-center gap-x-3 gap-y-1">
          <span
            className={[
              "inline-flex items-center rounded-controle border px-2 py-0.5 text-sm",
              style.texte,
              style.bordure,
            ].join(" ")}
          >
            {style.etiquette}
          </span>
          {course.retardMin > 0 && !style.perime && (
            <span className={["chiffres text-sm", style.texte].join(" ")}>
              retard de {course.retardMin} min
            </span>
          )}
          {course.causeRetard && (
            <span className="text-sm text-ardoise-700">
              Cause : {LIBELLES_CAUSE[course.causeRetard]}
            </span>
          )}
        </div>
      </header>

      <ListeArrets passages={passages} className="mt-6" />
    </div>
  );
}
