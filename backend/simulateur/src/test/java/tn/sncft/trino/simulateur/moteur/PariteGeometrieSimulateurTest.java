package tn.sncft.trino.simulateur.moteur;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tn.sncft.trino.simulateur.dto.CourseDuJourDTO;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins {@link GeometrieCourse} to {@code backend/parite-geometrie.json} — the
 * same file {@code PariteGeometrieApiTest} pins {@code GeometrieLigne} to.
 *
 * <p>{@code GeometrieCourse} duplicates the API's implementation on purpose:
 * these are two processes coupled by an HTTP contract and nothing else, and real
 * AVL hardware does not link against Trino (invariant 3). The duplication is a
 * decision, but until this test existed nothing detected the two copies drifting
 * apart — and the way that failure presents is trains drawn off the track, with
 * every number self-consistent and nothing logged.
 *
 * <p>A shared fixture rather than a shared module: this test reads a file, it
 * does not import anything from {@code api}, and the simulator's dependencies
 * are unchanged.
 */
class PariteGeometrieSimulateurTest {

    private static final String NOM_FIXTURE = "parite-geometrie.json";

    /** Coordinates agree to seven decimal places, about a centimetre. */
    private static final double TOLERANCE = 1e-7;

    @Test
    @DisplayName("GeometrieCourse reproduit les mêmes positions que GeometrieLigne")
    void positionsIdentiquesACellesDeLApi() throws Exception {
        JsonNode fixture = new ObjectMapper().readTree(fichierFixture());
        GeometrieCourse geometrie = GeometrieCourse.depuis(versCourse(fixture));

        assertEquals(fixture.get("longueurTotale").asDouble(), geometrie.longueurTotale(),
                TOLERANCE, "longueur totale");

        for (JsonNode attendu : fixture.get("attendus")) {
            double pk = attendu.get("pkKm").asDouble();
            double[] point = geometrie.positionA(pk);
            assertEquals(attendu.get("latitude").asDouble(), point[0],
                    TOLERANCE, "latitude à pk=" + pk);
            assertEquals(attendu.get("longitude").asDouble(), point[1],
                    TOLERANCE, "longitude à pk=" + pk);
        }
    }

    /**
     * Builds the {@code courses-du-jour} payload the simulator would have
     * received for this ligne, in the ALLER direction.
     *
     * <p>ALLER because that is the direction the trace is published in, and the
     * fixture's expected coordinates come from {@code GeometrieLigne}, which only
     * ever sees the stored direction. A RETOUR course walks the polyline
     * backwards against stops already mirrored to ascending chainage — worth its
     * own fixture, not worth conflating with this one.
     */
    private static CourseDuJourDTO versCourse(JsonNode fixture) {
        List<List<Double>> trace = new ArrayList<>();
        for (JsonNode point : fixture.get("trace")) {
            trace.add(List.of(point.get(0).asDouble(), point.get(1).asDouble()));
        }
        List<CourseDuJourDTO.ArretDTO> arrets = new ArrayList<>();
        for (JsonNode arret : fixture.get("arrets")) {
            arrets.add(new CourseDuJourDTO.ArretDTO(
                    null,
                    arret.get("code").asText(),
                    arret.get("nom").asText(),
                    arret.get("ordre").asInt(),
                    arret.get("pkKm").asDouble(),
                    arret.get("latitude").asDouble(),
                    arret.get("longitude").asDouble(),
                    null,
                    null));
        }
        return new CourseDuJourDTO(1L, "ALLER", null, null, 0.0, null, null, trace, arrets);
    }

    /**
     * Walks up from the working directory to {@code backend/}. Maven sets
     * {@code user.dir} to the module directory, but running one module on its own
     * and running the whole reactor start from different places.
     */
    private static File fichierFixture() {
        File repertoire = new File(System.getProperty("user.dir")).getAbsoluteFile();
        for (int remontees = 0; repertoire != null && remontees < 5; remontees++) {
            File candidat = new File(repertoire, NOM_FIXTURE);
            if (candidat.isFile()) {
                return candidat;
            }
            repertoire = repertoire.getParentFile();
        }
        throw new IllegalStateException(
                NOM_FIXTURE + " introuvable depuis " + System.getProperty("user.dir")
                        + ". Le fixture de parité doit rester à la racine de backend/.");
    }
}
