import { CarteReseau } from "@/components/CarteReseau";

// The map is the page: full-bleed, no hero, no marketing, no centred card.
// CarteReseau owns its own snapshot fetch (lignes, gares, day's running and
// delayed courses) and its own live channels -- this Server Component is
// only the shell around it. Chrome (wordmark, search, exploitation link) now
// lives in `app/(public)/layout.tsx`'s `EntetePublique`, shared with the gare
// and train pages -- see phase-6.md, "A minimal public header".
export default function Accueil() {
  return (
    <main className="relative h-full">
      <CarteReseau />
    </main>
  );
}
