import Link from "next/link";
import { BarreRecherche } from "./BarreRecherche";

/**
 * The public portal's only chrome: a slim bar over the full-bleed map (and,
 * in normal document flow, above the gare/train pages too -- see
 * `app/(public)/layout.tsx`). Hairline bottom border, no shadow, the SNCFT
 * wordmark set as type in `--sncft-bleu` (never a raster logo), the existing
 * search, and a discreet link into the exploitation console. Nothing else --
 * no menu, no hamburger. `/affichage/{gareId}` is a kiosk and stays entirely
 * outside this layout (`app/affichage/layout.tsx` is its own chrome-free
 * shell); `/exploitation/*` has its own gated shell too.
 */
export function EntetePublique() {
  return (
    <header className="flex h-12 shrink-0 items-center gap-2 border-b border-filet bg-papier px-3 sm:gap-4 sm:px-4">
      <Link
        href="/"
        className="shrink-0 font-condensee text-base font-medium tracking-tight text-sncft-bleu sm:text-lg"
      >
        SNCFT
      </Link>

      <div className="min-w-0 flex-1">
        <BarreRecherche className="w-full max-w-md" />
      </div>

      <Link
        href="/connexion"
        className="shrink-0 text-xs text-ardoise-400 underline decoration-filet underline-offset-2 hover:text-ardoise-700 sm:text-sm"
      >
        Espace exploitation
      </Link>
    </header>
  );
}
