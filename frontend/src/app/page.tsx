import { BarreRecherche } from "@/components/BarreRecherche";
import { CarteReseau } from "@/components/CarteReseau";

// The map is the page: full-bleed, no hero, no marketing, no centred card.
// CarteReseau owns its own snapshot fetch (lignes, gares, day's running and
// delayed courses) and its own live channels -- this Server Component is
// only the shell around it.
export default function Accueil() {
  return (
    <main className="flex h-screen flex-col overflow-hidden">
      <header className="flex h-12 shrink-0 items-center border-b border-filet bg-sncft-bleu px-4">
        <p className="font-condensee text-base font-medium text-papier">Trino</p>
      </header>

      <div className="relative flex-1">
        <CarteReseau />

        <div className="pointer-events-none absolute inset-x-0 top-0 z-20 flex justify-center px-4 pt-4">
          <div className="pointer-events-auto w-full max-w-md">
            <BarreRecherche />
          </div>
        </div>
      </div>
    </main>
  );
}
