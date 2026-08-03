import { apiGet, ApiError } from "@/lib/api";
import type { Gare, PageDTO } from "@/lib/types";

// Phase 0 placeholder: proves the Next.js app can reach the Spring Boot API.
// This whole page is replaced by the real map view in a later phase.
// Forced dynamic so the fetch happens per-request, not at build time (the
// backend is not necessarily up when `next build` runs).
export const dynamic = "force-dynamic";

async function chargerGares(): Promise<
  { ok: true; gares: Gare[] } | { ok: false; message: string }
> {
  try {
    const page = await apiGet<PageDTO<Gare>>("/gares?taille=200");
    return { ok: true, gares: page.contenu };
  } catch (erreur) {
    const message =
      erreur instanceof ApiError
        ? erreur.message
        : "Erreur inconnue lors de la récupération des gares.";
    return { ok: false, message };
  }
}

export default async function Accueil() {
  const resultat = await chargerGares();

  return (
    <main className="min-h-screen p-6 sm:p-10">
      <h1 className="text-2xl font-semibold">Gares du réseau SNCFT</h1>

      {!resultat.ok && (
        <p className="mt-6 rounded border border-red-300 bg-red-50 p-4 text-red-800">
          Impossible de charger les gares : {resultat.message}
        </p>
      )}

      {resultat.ok && resultat.gares.length === 0 && (
        <p className="mt-6 text-gray-600">Aucune gare enregistrée.</p>
      )}

      {resultat.ok && resultat.gares.length > 0 && (
        <div className="mt-6 overflow-x-auto">
          <table className="min-w-full border-collapse text-left text-sm">
            <thead>
              <tr className="border-b border-gray-300">
                <th className="py-2 pr-4 font-medium">Nom</th>
                <th className="py-2 pr-4 font-medium">Code</th>
                <th className="py-2 pr-4 font-medium">Région</th>
              </tr>
            </thead>
            <tbody>
              {resultat.gares.map((gare) => (
                <tr key={gare.id} className="border-b border-gray-100">
                  <td className="py-2 pr-4">{gare.nom}</td>
                  <td className="py-2 pr-4">{gare.code}</td>
                  <td className="py-2 pr-4">{gare.region}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </main>
  );
}
