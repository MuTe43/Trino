/**
 * Success/failure of one login attempt, shared by the console's landing page
 * and the full journal so the two never drift apart.
 *
 * Both class strings are spelled out in full. Tailwind 4 generates only the
 * utilities it can literally see in the source, so `` text-${...} `` would
 * compile, lint and build green while producing no colour at all (invariant 8).
 */
export default function IndicateurSucces({ succes }: { succes: boolean }) {
  return succes ? (
    <span className="text-statut-a-lheure">Réussie</span>
  ) : (
    <span className="text-statut-r60">Échouée</span>
  );
}
