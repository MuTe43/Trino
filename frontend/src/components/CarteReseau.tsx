"use client";

import { useEffect, useReducer, useRef, useState } from "react";
import type { Dispatch } from "react";
import Link from "next/link";
import maplibregl from "maplibre-gl";
import "maplibre-gl/dist/maplibre-gl.css";
import type { GeoJSONSource, Map as CarteMapLibre, Marker as MarqueurMapLibre } from "maplibre-gl";
import { createRoot } from "react-dom/client";
import type { Root } from "react-dom/client";
import { listerCourses, listerGares, listerLignes } from "@/lib/api";
import { useFluxSse } from "@/lib/sse";
import { styleStatut } from "@/lib/couleurs";
import { formaterHeure } from "@/lib/temps";
import { MarqueurTrain } from "./MarqueurTrain";
import type {
  CourseResumeDTO,
  EvenementPosition,
  EvenementRetard,
  EvenementStatut,
  Gare,
  LigneDTO,
  PageDTO,
  StatutCourse,
} from "@/lib/types";

// Chrome, not a feature: literal copies of --sncft-bleu / --filet / --papier.
// MapLibre paint properties parse their own colour expressions and cannot
// read CSS custom properties, so these cannot be sourced from couleurs.ts or
// globals.css at runtime -- they must stay in sync with those tokens by hand.
const COULEUR_TRACE_LIGNE = "#1D2B7D";
const COULEUR_ANNEAU_GARE = "#6B7CB8";
const COULEUR_FOND_GARE = "#F7F8FB";

const ATTRIBUTION_OSM =
  '&copy; <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener noreferrer">contributeurs OpenStreetMap</a>';

const STATUTS_AFFICHES_SUR_CARTE = new Set<StatutCourse>([
  "EN_CIRCULATION",
  "RETARDE",
  "ARRET_EXCEPTIONNEL",
]);

/** Today's date in Africa/Tunis, as the yyyy-MM-dd the API's `date` param expects. */
function dateDuJourTunis(): string {
  return new Intl.DateTimeFormat("en-CA", { timeZone: "Africa/Tunis" }).format(new Date());
}

/** Pages through a listing endpoint until every row is collected. Lignes and
 * gares are both small, fixed référentiel tables (~5 and ~40 rows), but
 * pagination is clamped server-side, so a single oversized `taille` is not
 * guaranteed to return everything in one page. */
async function chargerTout<T>(
  page: (p: number, taille: number) => Promise<PageDTO<T>>,
  taille = 100,
): Promise<T[]> {
  const resultat: T[] = [];
  let p = 0;
  for (;;) {
    const reponse = await page(p, taille);
    resultat.push(...reponse.contenu);
    if (reponse.contenu.length === 0 || resultat.length >= reponse.total) {
      break;
    }
    p += 1;
  }
  return resultat;
}

/**
 * The snapshot the map opens on, exactly as the phase brief specifies it:
 * `GET /courses?date=today&statut=EN_CIRCULATION,RETARDE`. `statut` binds a
 * list server-side, so this is one request.
 *
 * `ARRET_EXCEPTIONNEL` is deliberately not requested here. A course only
 * reaches that status by going silent, and the map keeps such a course once
 * it already holds it (see STATUTS_AFFICHES_SUR_CARTE) -- but a fresh page
 * load has no reason to open on trains whose feed is already dead.
 */
async function chargerInstantane(date: string): Promise<CourseResumeDTO[]> {
  const instantane = await listerCourses({
    date,
    statut: ["EN_CIRCULATION", "RETARDE"],
    taille: 200,
  });
  return instantane.contenu;
}

type EtatCourses = Map<number, CourseResumeDTO>;

type ActionCourses =
  | { type: "INIT"; courses: CourseResumeDTO[] }
  | { type: "POSITION"; evenement: EvenementPosition }
  | { type: "STATUT"; evenement: EvenementStatut }
  | { type: "RETARD"; evenement: EvenementRetard };

/**
 * `position` moves a marker, `statut` restyles it, `retard` recolours it --
 * each delta patches only the one entry it names. A delta for a course the
 * snapshot never loaded is dropped: SSE payloads are deltas, never a
 * snapshot, and none of these three carries enough fields (numéro, ligne,
 * nom...) to materialise a whole new CourseResumeDTO from scratch.
 */
function reducerCourses(etat: EtatCourses, action: ActionCourses): EtatCourses {
  switch (action.type) {
    case "INIT": {
      const suivant: EtatCourses = new Map();
      action.courses.forEach((course) => suivant.set(course.id, course));
      return suivant;
    }
    case "POSITION": {
      const course = etat.get(action.evenement.courseId);
      if (!course) return etat;
      const suivant = new Map(etat);
      suivant.set(course.id, {
        ...course,
        position: {
          latitude: action.evenement.latitude,
          longitude: action.evenement.longitude,
          vitesseKmh: action.evenement.vitesseKmh,
        },
        etaSuivante: action.evenement.etaSuivante,
      });
      return suivant;
    }
    case "STATUT": {
      const course = etat.get(action.evenement.courseId);
      if (!course) return etat;
      const suivant = new Map(etat);
      suivant.set(course.id, {
        ...course,
        statut: action.evenement.statut,
        retardMin: action.evenement.retardMin,
        classeRetard: action.evenement.classeRetard,
        causeRetard: action.evenement.causeRetard,
      });
      return suivant;
    }
    case "RETARD": {
      const course = etat.get(action.evenement.courseId);
      if (!course) return etat;
      const suivant = new Map(etat);
      suivant.set(course.id, {
        ...course,
        retardMin: action.evenement.retardMin,
        classeRetard: action.evenement.classeRetard,
        causeRetard: action.evenement.causeRetard,
      });
      return suivant;
    }
    default:
      return etat;
  }
}

/** Renders nothing: exists only to own exactly one `useFluxSse('ligne:{id}')`
 * per ligne without breaking the rules of hooks (the ligne count is dynamic,
 * so the hook cannot be called in a loop inside CarteReseau itself). Keyed by
 * ligne id in the parent, so mounting/unmounting tracks the visible lignes
 * one-to-one and each channel closes on unmount exactly once. */
function CanalSseLigne({ ligneId, dispatch }: { ligneId: number; dispatch: Dispatch<ActionCourses> }) {
  useFluxSse(`ligne:${ligneId}`, {
    onPosition: (evenement) => dispatch({ type: "POSITION", evenement }),
    onStatut: (evenement) => dispatch({ type: "STATUT", evenement }),
    onRetard: (evenement) => dispatch({ type: "RETARD", evenement }),
  });
  return null;
}

function PanneauDetail({ course, onFermer }: { course: CourseResumeDTO; onFermer: () => void }) {
  useEffect(() => {
    function surTouche(e: globalThis.KeyboardEvent) {
      if (e.key === "Escape") onFermer();
    }
    document.addEventListener("keydown", surTouche);
    return () => document.removeEventListener("keydown", surTouche);
  }, [onFermer]);

  const style = styleStatut(course.statut, course.classeRetard);

  return (
    <div
      role="dialog"
      aria-label={`Détail de la course ${course.numeroTrain}`}
      className="fixed inset-x-0 bottom-0 z-20 border-t border-filet bg-papier p-4 sm:absolute sm:inset-x-auto sm:right-4 sm:bottom-4 sm:w-96 sm:rounded-carte sm:border"
    >
      <div className="flex items-start justify-between gap-3">
        <div>
          <p className="chiffres text-lg">{course.numeroTrain}</p>
          <p className="font-condensee text-base leading-tight">{course.nomTrain}</p>
        </div>
        <button
          type="button"
          onClick={onFermer}
          aria-label="Fermer le détail"
          className="rounded-controle border border-filet px-2 py-1 text-sm text-ardoise-700"
        >
          Fermer
        </button>
      </div>

      <p className={["mt-2 text-sm", style.texte].join(" ")}>
        {style.etiquette}
        {course.retardMin > 0 && !style.perime ? (
          <span className="chiffres"> · retard de {course.retardMin} min</span>
        ) : null}
      </p>

      {course.gareSuivante && (
        <p className="mt-2 text-sm text-ardoise-700">
          Prochain arrêt : <span className="font-condensee">{course.gareSuivante.nom}</span>
          {course.etaSuivante && (
            <span className="chiffres"> · {formaterHeure(course.etaSuivante)}</span>
          )}
        </p>
      )}

      <Link href={`/trains/${course.id}`} className="mt-3 inline-block text-sm text-sncft-bleu">
        Voir la fiche du train
      </Link>
    </div>
  );
}

/**
 * The network map: OSM raster tiles, ligne traces and gare dots as quiet
 * chrome, trains as the only saturated colour. Fetches the day's running and
 * delayed courses once, then keeps them live over one SSE channel per ligne.
 */
export function CarteReseau() {
  const conteneurRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<CarteMapLibre | null>(null);
  const marqueursRef = useRef<Map<number, { marker: MarqueurMapLibre; root: Root }>>(new Map());
  const ajusteRef = useRef(false);

  const [carteChargee, setCarteChargee] = useState(false);
  const [lignes, setLignes] = useState<LigneDTO[]>([]);
  const [lignesVisibles, setLignesVisibles] = useState<number[]>([]);
  const [gares, setGares] = useState<Gare[]>([]);
  const [courses, dispatch] = useReducer(reducerCourses, new Map<number, CourseResumeDTO>());
  const [selectionId, setSelectionId] = useState<number | null>(null);
  const [erreurChargement, setErreurChargement] = useState<string | null>(null);

  // Create the map once.
  useEffect(() => {
    if (!conteneurRef.current) return;

    const map = new maplibregl.Map({
      container: conteneurRef.current,
      attributionControl: false,
      style: {
        version: 8,
        sources: {
          osm: {
            type: "raster",
            tiles: ["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
            tileSize: 256,
            maxzoom: 19,
            attribution: ATTRIBUTION_OSM,
          },
        },
        layers: [{ id: "osm", type: "raster", source: "osm" }],
      },
      center: [9.5, 34],
      zoom: 6,
    });
    map.addControl(new maplibregl.AttributionControl({ compact: true }));
    map.addControl(new maplibregl.NavigationControl({ showCompass: false }), "top-right");
    map.on("load", () => setCarteChargee(true));
    mapRef.current = map;

    return () => {
      map.remove();
      mapRef.current = null;
      setCarteChargee(false);
    };
  }, []);

  // Initial snapshot: lignes, gares, and the day's running/delayed courses.
  useEffect(() => {
    let annule = false;
    (async () => {
      try {
        const date = dateDuJourTunis();
        const [lignesChargees, garesChargees, instantane] = await Promise.all([
          chargerTout((p, t) => listerLignes(p, t)),
          chargerTout((p, t) => listerGares(p, t)),
          chargerInstantane(date),
        ]);
        if (annule) return;
        setLignes(lignesChargees);
        setGares(garesChargees);
        dispatch({ type: "INIT", courses: instantane });
      } catch {
        if (!annule) {
          setErreurChargement("Impossible de charger le réseau. Réessayez plus tard.");
        }
      }
    })();
    return () => {
      annule = true;
    };
  }, []);

  /**
   * Track which lignes are actually on screen, and subscribe only to those.
   *
   * The phase brief says "open SSE on the visible lignes"; subscribing to the
   * whole référentiel instead costs one permanent socket per ligne, and a
   * browser allows only ~6 per origin over HTTP/1.1. Those sockets are shared
   * with every REST call to the same API, so a network with enough lignes
   * starves `/recherche` and the kiosk's resync with no visible error.
   *
   * Note this does NOT help at the default full-network zoom, where every
   * ligne is visible by definition -- see the phase-7 note in STATE.md. It
   * bounds the cost as soon as the user zooms into a region, which is the
   * common case once the demo moves past its opening screen.
   */
  useEffect(() => {
    const map = mapRef.current;
    if (!map || !carteChargee) return;

    const recalculer = () => {
      const cadre = map.getBounds();
      // Vertex-in-bounds: a ligne crossing the viewport with no vertex inside
      // it is missed. With the seed's trace density that does not happen, and
      // the cost of being wrong is one channel, not a wrong render.
      const visibles = lignes
        .filter((ligne) => ligne.trace.some(([lon, lat]) => cadre.contains([lon, lat])))
        .map((ligne) => ligne.id);
      setLignesVisibles((precedent) =>
        precedent.length === visibles.length && precedent.every((id, i) => id === visibles[i])
          ? precedent // same set: keep the identity so no channel is torn down
          : visibles,
      );
    };

    recalculer();
    map.on("moveend", recalculer);
    return () => {
      map.off("moveend", recalculer);
    };
  }, [carteChargee, lignes]);

  // Draw ligne traces and gare dots once the map and the référentiel are ready.
  useEffect(() => {
    const map = mapRef.current;
    if (!map || !carteChargee) return;
    if (lignes.length === 0 && gares.length === 0) return;

    const geojsonLignes: GeoJSON.FeatureCollection<GeoJSON.LineString, { nom: string }> = {
      type: "FeatureCollection",
      features: lignes
        .filter((ligne) => ligne.trace.length >= 2)
        .map((ligne) => ({
          type: "Feature",
          properties: { nom: ligne.nom },
          geometry: { type: "LineString", coordinates: ligne.trace },
        })),
    };

    const geojsonGares: GeoJSON.FeatureCollection<GeoJSON.Point, { nom: string }> = {
      type: "FeatureCollection",
      features: gares.map((gare) => ({
        type: "Feature",
        properties: { nom: gare.nom },
        geometry: { type: "Point", coordinates: [gare.longitude, gare.latitude] },
      })),
    };

    const sourceLignes = map.getSource("lignes") as GeoJSONSource | undefined;
    if (sourceLignes) {
      sourceLignes.setData(geojsonLignes);
    } else {
      map.addSource("lignes", { type: "geojson", data: geojsonLignes });
      map.addLayer({
        id: "lignes-trace",
        type: "line",
        source: "lignes",
        layout: { "line-cap": "round" },
        paint: { "line-color": COULEUR_TRACE_LIGNE, "line-width": 1.5, "line-opacity": 0.35 },
      });
    }

    const sourceGares = map.getSource("gares") as GeoJSONSource | undefined;
    if (sourceGares) {
      sourceGares.setData(geojsonGares);
    } else {
      map.addSource("gares", { type: "geojson", data: geojsonGares });
      map.addLayer({
        id: "gares-points",
        type: "circle",
        source: "gares",
        paint: {
          "circle-radius": 3.5,
          "circle-color": COULEUR_FOND_GARE,
          "circle-stroke-width": 1,
          "circle-stroke-color": COULEUR_ANNEAU_GARE,
        },
      });
    }

    if (!ajusteRef.current) {
      const bornes = new maplibregl.LngLatBounds();
      lignes.forEach((ligne) => ligne.trace.forEach((point) => bornes.extend(point)));
      gares.forEach((gare) => bornes.extend([gare.longitude, gare.latitude]));
      if (!bornes.isEmpty()) {
        map.fitBounds(bornes, { padding: 32, duration: 0 });
        ajusteRef.current = true;
      }
    }
  }, [carteChargee, lignes, gares]);

  // Create/update/remove one marker per course visible on the map.
  useEffect(() => {
    const map = mapRef.current;
    if (!map || !carteChargee) return;
    const marqueurs = marqueursRef.current;
    const idsVus = new Set<number>();

    courses.forEach((course) => {
      if (!course.position || !STATUTS_AFFICHES_SUR_CARTE.has(course.statut)) return;
      idsVus.add(course.id);

      let entree = marqueurs.get(course.id);
      if (!entree) {
        const element = document.createElement("div");
        const marker = new maplibregl.Marker({ element, anchor: "center" })
          .setLngLat([course.position.longitude, course.position.latitude])
          .addTo(map);
        entree = { marker, root: createRoot(element) };
        marqueurs.set(course.id, entree);
      }

      entree.root.render(
        <MarqueurTrain
          marker={entree.marker}
          course={course}
          selectionne={selectionId === course.id}
          onSelectionner={setSelectionId}
        />,
      );
    });

    marqueurs.forEach((entree, id) => {
      if (!idsVus.has(id)) {
        entree.marker.remove();
        entree.root.unmount();
        marqueurs.delete(id);
      }
    });
  }, [courses, carteChargee, selectionId]);

  // Unmount cleanup: remove every marker/root left when the whole map goes away.
  useEffect(() => {
    const marqueurs = marqueursRef.current;
    return () => {
      marqueurs.forEach(({ marker, root }) => {
        marker.remove();
        root.unmount();
      });
      marqueurs.clear();
    };
  }, []);

  const selectionCourse = selectionId !== null ? (courses.get(selectionId) ?? null) : null;

  return (
    <div className="relative h-full min-h-[420px] w-full">
      <div
        ref={conteneurRef}
        role="application"
        aria-label="Carte du réseau ferroviaire"
        className="h-full min-h-[420px] w-full"
      />

      {lignesVisibles.map((ligneId) => (
        <CanalSseLigne key={ligneId} ligneId={ligneId} dispatch={dispatch} />
      ))}

      {erreurChargement && (
        <p
          role="alert"
          className="absolute top-4 left-4 z-10 rounded-carte border border-filet bg-papier px-3 py-2 text-sm text-statut-r60"
        >
          {erreurChargement}
        </p>
      )}

      {selectionCourse && (
        <PanneauDetail course={selectionCourse} onFermer={() => setSelectionId(null)} />
      )}
    </div>
  );
}
