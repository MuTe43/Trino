import Link from "next/link";
import { notFound } from "next/navigation";
import { ApiError, listerDeparts, trouverGare } from "@/lib/api";
import { TableauDepartsGare } from "@/components/TableauDepartsGare";

// Live data (SSE) means this page must never be statically cached.
export const dynamic = "force-dynamic";

interface PageProps {
  params: Promise<{ id: string }>;
}

export default async function GarePage({ params }: PageProps) {
  const { id } = await params;
  const gareId = Number(id);
  if (!Number.isInteger(gareId)) {
    notFound();
  }

  let gare;
  let departs;
  try {
    [gare, departs] = await Promise.all([trouverGare(gareId), listerDeparts(gareId, 20)]);
  } catch (erreur) {
    if (erreur instanceof ApiError && erreur.statut === 404) {
      notFound();
    }
    throw erreur;
  }

  return (
    <main className="mx-auto max-w-2xl px-4 py-6 sm:px-6">
      <Link href="/" className="text-sm text-sncft-bleu">
        ← Carte du réseau
      </Link>

      <header className="mt-4 border-b border-filet pb-4">
        <h1 className="font-condensee text-2xl leading-tight text-encre">{gare.nom}</h1>
        <p className="mt-1 text-sm text-ardoise-700">
          {gare.region} · <span className="chiffres">{gare.nbQuais}</span> quai
          {gare.nbQuais > 1 ? "s" : ""}
        </p>
      </header>

      <TableauDepartsGare gareId={gare.id} departsInitiaux={departs} />

      <p className="mt-6 text-sm">
        <Link
          href={`/affichage/${gare.id}`}
          className="text-ardoise-400 underline decoration-filet underline-offset-2 hover:text-ardoise-700"
        >
          Ouvrir l&apos;écran d&apos;affichage de cette gare
        </Link>
      </p>
    </main>
  );
}
