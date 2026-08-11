"use client";

import { useEffect, useReducer, useRef, useState } from "react";
import type { Dispatch } from "react";
import Link from "next/link";
import maplibregl from "maplibre-gl";
import "maplibre-gl/dist/maplibre-gl.css";
import type { GeoJSONSource, Map as CarteMapLibre, Marker as MarqueurMapLibre } from "maplibre-gl";
import { createRoot } from "react-dom/client";
import type { Root } from "react-dom/client";
import { chargerToutesPages, listerCourses, listerGares, listerLignes } from "@/lib/api";
import { listerIncidentsOuverts } from "@/lib/incidents";
import { useFluxSse } from "@/lib/sse";
import { libelleTypeIncident, styleGravite, styleStatut } from "@/lib/couleurs";
import { formaterDateHeure, formaterHeure } from "@/lib/temps";
import { MarqueurTrain } from "./MarqueurTrain";
import { MarqueurIncident, type IncidentCarte } from "./MarqueurIncident";
import type {
  CourseResumeDTO,
  EvenementIncident,
  EvenementPosition,
  EvenementRetard,
  EvenementStatut,
  Gare,
  IncidentDTO,
  LigneDTO,
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
 * one-to-one and each channel closes on unmount exactly once.
 *
 * `onIncident` is only ever passed in supervision mode (see `CarteReseau`'s
 * `supervisionIncidents` prop) -- the public map never asks for it, so it
 * never opens the incidents endpoint's auth dependency.
 *
 * The ligne channels alone are enough to see every incident on the network:
 * `DiffuseurIncident` fans a gare-attached incident out to every ligne that
 * serves that gare (via desserte), so a gare-only incident (`ligneId: null`
 * on the payload) still arrives here, on whichever ligne(s) call at that
 * gare. `gare:{id}` still carries it too -- that channel exists for the
 * station board, which watches one gare -- but subscribing to all ~40 of
 * them here as well would be a de-facto global channel under a different
 * name, exactly what invariant 5 rules out, and one this component does not
 * need. Do not key any incident handling off `evenement.ligneId` being
 * non-null: it is legitimately null for a gare-only incident even on this
 * channel. */
function CanalSseLigne({
  ligneId,
  dispatch,
  onIncident,
}: {
  ligneId: number;
  dispatch: Dispatch<ActionCourses>;
  onIncident?: (evenement: EvenementIncident) => void;
}) {
  useFluxSse(`ligne:${ligneId}`, {
    onPosition: (evenement) => dispatch({ type: "POSITION", evenement }),
    onStatut: (evenement) => dispatch({ type: "STATUT", evenement }),
    onRetard: (evenement) => dispatch({ type: "RETARD", evenement }),
    onIncident,
  });
  return null;
}

type EtatIncidents = Map<number, IncidentCarte>;

type ActionIncidents =
  | { type: "INIT_INCIDENTS"; incidents: IncidentDTO[] }
  | { type: "INCIDENT"; evenement: EvenementIncident };

/** `IncidentDTO` (REST snapshot) carries no coordinates -- only the SSE delta
 * does (see `EvenementIncident`'s doc comment in types.ts). Normalised here so
 * the reducer and the marker layer only ever handle one shape. */
function incidentCarteDepuisDTO(dto: IncidentDTO): IncidentCarte {
  return {
    id: dto.id,
    type: dto.type,
    gravite: dto.gravite,
    statut: dto.statut,
    description: dto.description,
    survenuAt: dto.survenuAt,
    ligneId: dto.ligne?.id ?? null,
    gareId: dto.gare?.id ?? null,
    courseId: dto.course?.id ?? null,
    latitude: null,
    longitude: null,
  };
}

function incidentCarteDepuisEvenement(evenement: EvenementIncident): IncidentCarte {
  return {
    id: evenement.incidentId,
    type: evenement.type,
    gravite: evenement.gravite,
    statut: evenement.statut,
    description: evenement.description,
    survenuAt: evenement.survenuAt,
    ligneId: evenement.ligneId,
    gareId: evenement.gareId,
    courseId: evenement.courseId,
    latitude: evenement.latitude,
    longitude: evenement.longitude,
  };
}

/**
 * A resolved incident drops off the supervision map outright -- `ouverts()`
 * never returns it again either, so keeping a greyed-out marker around would
 * only ever be wrong the moment the page reloads. Anything else upserts.
 */
function reducerIncidents(etat: EtatIncidents, action: ActionIncidents): EtatIncidents {
  switch (action.type) {
    case "INIT_INCIDENTS": {
      const suivant: EtatIncidents = new Map();
      action.incidents.forEach((dto) => suivant.set(dto.id, incidentCarteDepuisDTO(dto)));
      return suivant;
    }
    case "INCIDENT": {
      const suivant = new Map(etat);
      if (action.evenement.statut === "RESOLU") {
        suivant.delete(action.evenement.incidentId);
      } else {
        suivant.set(action.evenement.incidentId, incidentCarteDepuisEvenement(action.evenement));
      }
      return suivant;
    }
    default:
      return etat;
  }
}

interface Coordonnee {
  lon: number;
  lat: number;
}

/**
 * Where to plant an incident's marker: the coordinates the event already
 * carries (server-computed, most authoritative) if present, else the gare it
 * is attached to, else the last known position of the course it is attached
 * to. Null when none apply -- a ligne-wide incident with no positioned course
 * has no point, and the caller must list it rather than invent one.
 */
function positionIncident(
  incident: IncidentCarte,
  gares: Gare[],
  courses: Map<number, CourseResumeDTO>,
): Coordonnee | null {
  if (incident.latitude !== null && incident.longitude !== null) {
    return { lon: incident.longitude, lat: incident.latitude };
  }
  if (incident.gareId !== null) {
    const gare = gares.find((g) => g.id === incident.gareId);
    if (gare) return { lon: gare.longitude, lat: gare.latitude };
  }
  if (incident.courseId !== null) {
    const course = courses.get(incident.courseId);
    if (course?.position) return { lon: course.position.longitude, lat: course.position.latitude };
  }
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

function PanneauDetailIncident({
  incident,
  gares,
  onFermer,
}: {
  incident: IncidentCarte;
  gares: Gare[];
  onFermer: () => void;
}) {
  useEffect(() => {
    function surTouche(e: globalThis.KeyboardEvent) {
      if (e.key === "Escape") onFermer();
    }
    document.addEventListener("keydown", surTouche);
    return () => document.removeEventListener("keydown", surTouche);
  }, [onFermer]);

  const style = styleGravite(incident.gravite);
  const gare = incident.gareId !== null ? gares.find((g) => g.id === incident.gareId) : undefined;

  return (
    <div
      role="dialog"
      aria-label="Détail de l'incident"
      className="fixed inset-x-0 bottom-0 z-20 border-t border-filet bg-papier p-4 sm:absolute sm:inset-x-auto sm:left-4 sm:bottom-4 sm:w-96 sm:rounded-carte sm:border"
    >
      <div className="flex items-start justify-between gap-3">
        <div>
          <p className={["inline-flex items-center rounded-controle border px-2 py-0.5 text-sm", style.texte, style.bordure].join(" ")}>
            {style.etiquette}
          </p>
          <p className="mt-1 font-condensee text-base leading-tight">
            {libelleTypeIncident(incident.type)}
          </p>
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

      <p className="mt-2 text-sm text-encre">{incident.description}</p>
      <p className="mt-2 text-xs text-ardoise-400">
        Signalé le {formaterDateHeure(incident.survenuAt)}
        {gare ? ` · ${gare.nom}` : ""}
      </p>

      {incident.courseId !== null && (
        <Link href={`/trains/${incident.courseId}`} className="mt-3 inline-block text-sm text-sncft-bleu">
          Voir la fiche du train concerné
        </Link>
      )}
    </div>
  );
}

/**
 * The network map: OSM raster tiles, ligne traces and gare dots as quiet
 * chrome, trains as the only saturated colour. Fetches the day's running and
 * delayed courses once, then keeps them live over one SSE channel per ligne.
 *
 * `supervisionIncidents` turns on the exploitation console's superset: open
 * incidents load from `/incidents/ouverts`, stay live via the same ligne
 * channels already open for course tracking (`DiffuseurIncident` fans a
 * gare-attached incident out to every ligne serving that gare, so no
 * separate gare subscription is needed here -- see `CanalSseLigne`'s doc
 * comment), and render as gravité-coloured pins alongside the trains. Off by
 * default -- the public map must not gain a bearer-token dependency or
 * channels it never asked for.
 */
export function CarteReseau({ supervisionIncidents = false }: { supervisionIncidents?: boolean } = {}) {
  const conteneurRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<CarteMapLibre | null>(null);
  const marqueursRef = useRef<Map<number, { marker: MarqueurMapLibre; root: Root }>>(new Map());
  const marqueursIncidentsRef = useRef<Map<number, { marker: MarqueurMapLibre; root: Root }>>(new Map());
  const ajusteRef = useRef(false);

  const [carteChargee, setCarteChargee] = useState(false);
  const [lignes, setLignes] = useState<LigneDTO[]>([]);
  const [lignesVisibles, setLignesVisibles] = useState<number[]>([]);
  const [gares, setGares] = useState<Gare[]>([]);
  const [courses, dispatch] = useReducer(reducerCourses, new Map<number, CourseResumeDTO>());
  const [incidents, dispatchIncidents] = useReducer(reducerIncidents, new Map<number, IncidentCarte>());
  const [selectionId, setSelectionId] = useState<number | null>(null);
  const [incidentSelectionneId, setIncidentSelectionneId] = useState<number | null>(null);
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
          chargerToutesPages((p, t) => listerLignes(p, t)),
          chargerToutesPages((p, t) => listerGares(p, t)),
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

  // Supervision mode's own snapshot: open incidents, loaded separately from
  // lignes/gares/courses above so that a caller without the auth needed for
  // `/incidents/ouverts` (i.e. the public map, which never sets this prop)
  // never even attempts the call, and so that a failure here never blocks the
  // train map from loading.
  useEffect(() => {
    if (!supervisionIncidents) return;
    let annule = false;
    listerIncidentsOuverts()
      .then((incidentsOuverts) => {
        if (!annule) dispatchIncidents({ type: "INIT_INCIDENTS", incidents: incidentsOuverts });
      })
      .catch(() => {
        // Non-blocking: the map still works for course tracking without it.
      });
    return () => {
      annule = true;
    };
  }, [supervisionIncidents]);

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
          onSelectionner={(id) => {
            setSelectionId(id);
            // The two detail panels sit in opposite corners (see the return
            // below) precisely so they never have to fight for the same
            // space, but only one is ever useful at a time.
            setIncidentSelectionneId(null);
          }}
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

  // Create/update/remove one marker per positioned incident. Supervision mode
  // only -- `incidents` stays empty otherwise, so this is a no-op loop for
  // the public map.
  useEffect(() => {
    const map = mapRef.current;
    if (!map || !carteChargee || !supervisionIncidents) return;
    const marqueurs = marqueursIncidentsRef.current;
    const idsVus = new Set<number>();

    incidents.forEach((incident) => {
      const position = positionIncident(incident, gares, courses);
      if (!position) return; // no point -- listed in the panel instead
      idsVus.add(incident.id);

      let entree = marqueurs.get(incident.id);
      if (!entree) {
        const element = document.createElement("div");
        const marker = new maplibregl.Marker({ element, anchor: "center" })
          .setLngLat([position.lon, position.lat])
          .addTo(map);
        entree = { marker, root: createRoot(element) };
        marqueurs.set(incident.id, entree);
      } else {
        entree.marker.setLngLat([position.lon, position.lat]);
      }

      entree.root.render(
        <MarqueurIncident
          incident={incident}
          selectionne={incidentSelectionneId === incident.id}
          onSelectionner={(id) => {
            setIncidentSelectionneId(id);
            setSelectionId(null);
          }}
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
  }, [incidents, carteChargee, supervisionIncidents, gares, courses, incidentSelectionneId]);

  // Unmount cleanup for incident markers, mirroring the course markers' own.
  useEffect(() => {
    const marqueurs = marqueursIncidentsRef.current;
    return () => {
      marqueurs.forEach(({ marker, root }) => {
        marker.remove();
        root.unmount();
      });
      marqueurs.clear();
    };
  }, []);

  const selectionCourse = selectionId !== null ? (courses.get(selectionId) ?? null) : null;
  const incidentSelectionne =
    incidentSelectionneId !== null ? (incidents.get(incidentSelectionneId) ?? null) : null;

  // Full network coverage in supervision mode rather than the public map's
  // viewport-based subset: the network is small (~5 lignes) and a
  // responsable must never miss an incident because the map happened to be
  // panned elsewhere.
  const canauxLignes = supervisionIncidents ? lignes.map((ligne) => ligne.id) : lignesVisibles;

  const incidentsSansPosition = supervisionIncidents
    ? Array.from(incidents.values()).filter((incident) => positionIncident(incident, gares, courses) === null)
    : [];

  return (
    <div className="relative h-full min-h-[420px] w-full">
      <div
        ref={conteneurRef}
        role="application"
        aria-label="Carte du réseau ferroviaire"
        className="h-full min-h-[420px] w-full"
      />

      {canauxLignes.map((ligneId) => (
        <CanalSseLigne
          key={ligneId}
          ligneId={ligneId}
          dispatch={dispatch}
          onIncident={
            supervisionIncidents
              ? (evenement) => dispatchIncidents({ type: "INCIDENT", evenement })
              : undefined
          }
        />
      ))}

      <div className="pointer-events-none absolute top-4 left-4 z-10 flex flex-col gap-2">
        {erreurChargement && (
          <p
            role="alert"
            className="pointer-events-auto rounded-carte border border-filet bg-papier px-3 py-2 text-sm text-statut-r60"
          >
            {erreurChargement}
          </p>
        )}

        {incidentsSansPosition.length > 0 && (
          <div className="pointer-events-auto w-64 rounded-carte border border-filet bg-papier p-3 text-sm">
            <p className="mb-2 text-xs font-medium text-ardoise-400 uppercase">
              Incidents sans localisation ponctuelle
            </p>
            <ul className="flex flex-col gap-2">
              {incidentsSansPosition.map((incident) => {
                const style = styleGravite(incident.gravite);
                return (
                  <li key={incident.id}>
                    <button
                      type="button"
                      onClick={() => {
                        setIncidentSelectionneId(incident.id);
                        setSelectionId(null);
                      }}
                      className="w-full text-left"
                    >
                      <span className={["inline-block h-2 w-2 rounded-full", style.fond].join(" ")} aria-hidden="true" />
                      <span className="ml-1.5 text-encre">{libelleTypeIncident(incident.type)}</span>
                    </button>
                  </li>
                );
              })}
            </ul>
          </div>
        )}
      </div>

      {incidentSelectionne && (
        <PanneauDetailIncident
          incident={incidentSelectionne}
          gares={gares}
          onFermer={() => setIncidentSelectionneId(null)}
        />
      )}

      {selectionCourse && (
        <PanneauDetail course={selectionCourse} onFermer={() => setSelectionId(null)} />
      )}
    </div>
  );
}
