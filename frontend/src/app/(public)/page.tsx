import Link from "next/link";
import { BarreRecherche } from "@/components/BarreRecherche";
import { CarteReseau } from "@/components/CarteReseau";
import { LegendeRetards } from "@/components/LegendeRetards";
import { courseEnVedette, gareEnVedette } from "@/lib/serveur";

export const metadata = {
  title: "Trino — circulation des trains SNCFT en direct",
};

/**
 * The accueil.
 *
 * `/` used to be the full-screen map, which is now at `/carte`. A first-time
 * visitor landed on a field of coloured markers with nothing explaining what the
 * colours meant, that a train could be followed, or that station boards existed.
 * That is an onboarding gap, not a marketing one — so there is no hero image, no
 * feature card and no call to action here. It is a public service explaining
 * itself, in type and space.
 *
 * A Server Component. The map and the search box are the only client-side
 * things on the page, and the map is the real one at a reduced height, never a
 * screenshot: a front page showing trains that actually move is the single most
 * convincing thing this project has.
 *
 * Every id in a link below is resolved at request time — see `lib/serveur.ts`.
 */
export default async function Accueil() {
  // In parallel: two independent reads, and the page has nothing to do between
  // them. Neither throws -- both return null if the API is briefly unreachable,
  // and each section drops its link rather than the page failing.
  const [course, gare] = await Promise.all([courseEnVedette(), gareEnVedette()]);

  return (
    <main className="mx-auto w-full max-w-5xl px-4 pb-16 pt-8 sm:px-6 sm:pt-12">
      {/* 1. Bandeau */}
      <section>
        <h1 className="font-condensee text-3xl font-medium leading-tight tracking-tight text-sncft-bleu sm:text-4xl">
          Trino
        </h1>
        <p className="mt-2 max-w-xl text-base text-encre sm:text-lg">
          Suivez en direct la circulation des trains sur le réseau SNCFT.
        </p>
        {/* Larger than the header's copy, which is the point: the header's
            search is chrome a visitor has to notice, this one is an invitation. */}
        <BarreRecherche className="mt-5 w-full max-w-xl" />
        <p className="mt-2 text-xs text-ardoise-400">
          Numéro de train, gare, ligne ou destination.
        </p>
      </section>

      {/* 2. La carte, en direct */}
      <section className="mt-10">
        <div className="flex items-baseline justify-between gap-4">
          <h2 className="font-condensee text-lg font-medium text-encre">La carte, en direct</h2>
          <Link
            href="/carte"
            className="shrink-0 text-sm text-sncft-bleu underline decoration-filet underline-offset-2 hover:decoration-sncft-bleu"
          >
            Carte plein écran
          </Link>
        </div>
        {/* CarteReseau fills its container (h-full, min-h-[420px]); the fixed
            height here is the only difference from the full-screen route. Same
            component, same SSE subscriptions -- there is no second map. */}
        <div className="mt-3 h-[420px] overflow-hidden rounded-carte border border-filet">
          <CarteReseau />
        </div>
      </section>

      {/* 3. Légende des retards */}
      <section className="mt-10">
        <h2 className="font-condensee text-lg font-medium text-encre">Légende des retards</h2>
        <p className="mt-1 text-sm text-ardoise-400">
          La couleur d&apos;un train indique son retard sur l&apos;horaire prévu.
        </p>
        <LegendeRetards className="mt-4" />
      </section>

      {/* 4. Trois usages */}
      <section className="mt-10">
        <h2 className="font-condensee text-lg font-medium text-encre">Trois usages</h2>
        <ul className="mt-4 grid grid-cols-1 gap-px overflow-hidden rounded-carte border border-filet bg-filet sm:grid-cols-3">
          <Usage
            titre="Suivre un train"
            texte="Recevez une notification si votre train prend du retard, sans créer de compte."
            href={course ? `/trains/${course.id}` : null}
            libelleLien={course ? `Train ${course.numeroTrain}` : null}
          />
          <Usage
            titre="Écrans de gare"
            texte="L'affichage des prochains départs, conçu pour les écrans en gare."
            href={gare ? `/affichage/${gare.id}` : null}
            libelleLien={gare ? `Écran de ${gare.nom}` : null}
          />
          <Usage
            titre="Départs d'une gare"
            texte="Les prochains départs d'une gare, avec la voie et le retard de chaque train."
            href={gare ? `/gares/${gare.id}` : null}
            libelleLien={gare ? `Départs de ${gare.nom}` : null}
          />
        </ul>
      </section>

      {/* 5. Pied de page */}
      <footer className="mt-12 border-t border-filet pt-5 text-xs text-ardoise-400">
        <p className="max-w-xl">
          Les positions des trains sont transmises toutes les 5 secondes par les équipements
          embarqués. Les heures estimées sont recalculées à chaque position reçue à partir des
          horaires théoriques du plan de transport.
        </p>
        <p className="mt-3">
          <Link
            href="/connexion"
            className="underline decoration-filet underline-offset-2 hover:text-ardoise-700"
          >
            Espace exploitation
          </Link>
        </p>
      </footer>
    </main>
  );
}

/**
 * One of the three usage blocks. One sentence, one real link — not a feature
 * card: no icon, no border of its own, no shadow. The hairline grid comes from
 * the parent's `gap-px` over a `bg-filet` ground, which is the same way the
 * tables elsewhere in the product separate rows.
 *
 * `href` is nullable because the subject is derived at request time and the API
 * may be briefly unreachable. The block then renders its text without a link
 * rather than pointing at a route that cannot resolve.
 */
function Usage({
  titre,
  texte,
  href,
  libelleLien,
}: {
  titre: string;
  texte: string;
  href: string | null;
  libelleLien: string | null;
}) {
  return (
    <li className="bg-papier p-4">
      <h3 className="font-condensee text-base font-medium text-encre">{titre}</h3>
      <p className="mt-1.5 text-sm text-ardoise-700">{texte}</p>
      {href && libelleLien ? (
        <Link
          href={href}
          className="mt-3 inline-block text-sm text-sncft-bleu underline decoration-filet underline-offset-2 hover:decoration-sncft-bleu"
        >
          {libelleLien}
        </Link>
      ) : (
        <p className="mt-3 text-sm text-ardoise-400">Indisponible pour le moment.</p>
      )}
    </li>
  );
}
