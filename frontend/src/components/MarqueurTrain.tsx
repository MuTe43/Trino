"use client";

import { useEffect, useRef } from "react";
import type { Marker } from "maplibre-gl";
import type { CourseResumeDTO } from "@/lib/types";
import { styleStatut } from "@/lib/couleurs";

/** Roughly the simulator's update rate; deltas that land mid-flight retarget
 * from wherever the marker currently sits rather than restarting the clock. */
const DUREE_INTERPOLATION_MS = 5_000;

interface Coordonnee {
  lon: number;
  lat: number;
}

function easeSortie(t: number): number {
  return 1 - (1 - t) ** 3;
}

function lerpCoordonnee(depart: Coordonnee, cible: Coordonnee, t: number): Coordonnee {
  return {
    lon: depart.lon + (cible.lon - depart.lon) * t,
    lat: depart.lat + (cible.lat - depart.lat) * t,
  };
}

/**
 * One requestAnimationFrame loop shared by every mounted marker. A network
 * of a dozen simultaneous trains must drive a single rAF chain, not one per
 * marker -- listeners register/unregister and the loop stops itself once
 * nobody is watching.
 */
class BoucleAnimationPartagee {
  private readonly auditeurs = new Set<(heureMs: number) => void>();
  private idRaf: number | null = null;

  inscrire(auditeur: (heureMs: number) => void): () => void {
    this.auditeurs.add(auditeur);
    this.demarrer();
    return () => {
      this.auditeurs.delete(auditeur);
      if (this.auditeurs.size === 0) {
        this.arreter();
      }
    };
  }

  private demarrer(): void {
    if (this.idRaf !== null) {
      return;
    }
    const etape = (heureMs: number) => {
      this.auditeurs.forEach((auditeur) => auditeur(heureMs));
      this.idRaf = requestAnimationFrame(etape);
    };
    this.idRaf = requestAnimationFrame(etape);
  }

  private arreter(): void {
    if (this.idRaf !== null) {
      cancelAnimationFrame(this.idRaf);
      this.idRaf = null;
    }
  }
}

const boucleAnimation = new BoucleAnimationPartagee();

function prefereMouvementReduit(): boolean {
  if (typeof window === "undefined" || !window.matchMedia) {
    return false;
  }
  return window.matchMedia("(prefers-reduced-motion: reduce)").matches;
}

export interface MarqueurTrainProps {
  /** The MapLibre marker this element belongs to. CarteReseau owns creation
   * and removal; this component only ever repositions/repaints it. */
  marker: Marker;
  course: CourseResumeDTO;
  selectionne: boolean;
  onSelectionner: (courseId: number) => void;
}

/**
 * The DOM content of one course's marker on the network map. Interpolates
 * position across `position` deltas through a single shared rAF loop so
 * movement reads as continuous at the simulator's ~5s update rate instead of
 * teleporting -- the single most visible thing in this phase. `retard` and
 * `statut` deltas recolour/restyle it on the next render, no animation
 * involved. ARRET_EXCEPTIONNEL freezes the marker in place: the feed went
 * silent, so animating a position would be inventing data.
 */
export function MarqueurTrain({ marker, course, selectionne, onSelectionner }: MarqueurTrainProps) {
  const posActuelle = useRef<Coordonnee | null>(null);
  const posDepart = useRef<Coordonnee | null>(null);
  const posCible = useRef<Coordonnee | null>(null);
  const debutMs = useRef(0);
  const geleRef = useRef(false);

  const perime = course.statut === "ARRET_EXCEPTIONNEL";
  const latitude = course.position?.latitude;
  const longitude = course.position?.longitude;

  // Retarget (or adopt, on first mount) whenever the course's position moves.
  useEffect(() => {
    geleRef.current = perime;
    if (latitude === undefined || longitude === undefined) {
      return;
    }
    const cible: Coordonnee = { lon: longitude, lat: latitude };

    if (posActuelle.current === null) {
      // First known position for this course: snap, never animate in from nowhere.
      posActuelle.current = cible;
      posDepart.current = cible;
      posCible.current = cible;
      marker.setLngLat([cible.lon, cible.lat]);
      return;
    }

    if (perime) {
      // Signal lost: freeze exactly where the marker already sits.
      return;
    }

    posDepart.current = posActuelle.current;
    posCible.current = cible;
    debutMs.current = performance.now();
  }, [marker, latitude, longitude, perime]);

  // The shared animation loop: registered once per marker instance.
  useEffect(() => {
    const mouvementReduit = prefereMouvementReduit();
    return boucleAnimation.inscrire((heureMs) => {
      if (geleRef.current || posDepart.current === null || posCible.current === null) {
        return;
      }
      if (mouvementReduit) {
        posActuelle.current = posCible.current;
        marker.setLngLat([posCible.current.lon, posCible.current.lat]);
        return;
      }
      const t = Math.min(1, (heureMs - debutMs.current) / DUREE_INTERPOLATION_MS);
      const point = lerpCoordonnee(posDepart.current, posCible.current, easeSortie(t));
      posActuelle.current = point;
      marker.setLngLat([point.lon, point.lat]);
    });
  }, [marker]);

  const style = styleStatut(course.statut, course.classeRetard);
  const nomAccessible = `Train ${course.numeroTrain}, ${course.nomTrain}, ${style.etiquette}${
    course.retardMin > 0 && !style.perime
      ? `, ${course.retardMin} minute${course.retardMin > 1 ? "s" : ""} de retard`
      : ""
  }`;

  return (
    <button
      type="button"
      onClick={() => onSelectionner(course.id)}
      aria-label={nomAccessible}
      aria-pressed={selectionne}
      className={[
        "flex h-6 min-w-6 items-center justify-center rounded-controle border px-1",
        "font-ui text-[11px] leading-none",
        perime ? "bg-transparent" : style.fond,
        style.bordure,
        selectionne ? "ring-2 ring-encre ring-offset-1 ring-offset-papier" : "",
      ].join(" ")}
      style={{
        color: perime ? style.var : "var(--papier)",
        borderWidth: perime ? 2 : 1,
      }}
    >
      <span className="chiffres">{course.numeroTrain}</span>
    </button>
  );
}
