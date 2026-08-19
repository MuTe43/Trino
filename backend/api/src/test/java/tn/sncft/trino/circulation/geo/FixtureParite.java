package tn.sncft.trino.circulation.geo;

import java.io.File;

/**
 * Locates {@code backend/parite-geometrie.json}, the fixture both geometry
 * implementations are pinned against.
 *
 * <p>Deliberately a file outside either module's resources. {@code
 * GeometrieLigne} (api) and {@code GeometrieCourse} (simulateur) are duplicated
 * on purpose -- the HTTP contract is the only coupling either process is allowed
 * (invariant 3), and a shared jar would be the first step back towards the
 * design decision 2 rejects. But nothing stopped the two from drifting, and when
 * they do the symptom is trains rendered off the track with no error anywhere.
 *
 * <p>A shared fixture is what pins them without linking them: each module's test
 * asserts its own implementation against the same numbers, and neither module
 * depends on the other. The file is walked up to rather than resolved from a
 * fixed relative path, so the tests pass whether Maven runs from {@code backend}
 * or from a single module directory.
 */
final class FixtureParite {

    static final String NOM = "parite-geometrie.json";

    /** Coordinates agree to seven decimal places, about a centimetre. */
    static final double TOLERANCE = 1e-7;

    private FixtureParite() {
    }

    static File fichier() {
        File repertoire = new File(System.getProperty("user.dir")).getAbsoluteFile();
        for (int remontees = 0; repertoire != null && remontees < 5; remontees++) {
            File candidat = new File(repertoire, NOM);
            if (candidat.isFile()) {
                return candidat;
            }
            repertoire = repertoire.getParentFile();
        }
        throw new IllegalStateException(
                NOM + " introuvable depuis " + System.getProperty("user.dir")
                        + ". Le fixture de parité doit rester à la racine de backend/.");
    }
}
