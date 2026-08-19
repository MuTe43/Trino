import { CarteReseau } from "@/components/CarteReseau";
import { LegendeRetards } from "@/components/LegendeRetards";

export const metadata = {
  title: "Carte du réseau — Trino",
};

/**
 * The full-screen map, unchanged from phase 4 — it simply moved here.
 *
 * `/` used to be this page. It now opens on the accueil, which embeds the same
 * `CarteReseau` at a reduced height and explains what the colours mean; this
 * route is what the "Carte plein écran" link goes to, for a visitor who wants
 * the map and nothing else.
 *
 * The legend rides along as a collapsible panel. A map whose colours are
 * unexplained is unreadable regardless of which page it sits on, and the visitor
 * who came straight here has not passed the accueil's copy of it.
 *
 * `<details>` rather than state: the panel opens and closes with no JavaScript,
 * which keeps this a Server Component and keeps the map the only thing on the
 * route that costs the client bundle anything.
 */
export default function CartePleinEcran() {
  return (
    <main className="relative h-full">
      <CarteReseau />

      <details className="absolute bottom-3 left-3 z-10 max-w-[calc(100vw-1.5rem)] rounded-carte border border-filet bg-papier/95 backdrop-blur-sm sm:max-w-sm">
        <summary className="cursor-pointer list-none px-3 py-2 text-xs text-ardoise-700 marker:content-none">
          Légende des retards
        </summary>
        <div className="border-t border-filet px-3 py-2.5">
          <LegendeRetards colonnes={1} />
        </div>
      </details>
    </main>
  );
}
