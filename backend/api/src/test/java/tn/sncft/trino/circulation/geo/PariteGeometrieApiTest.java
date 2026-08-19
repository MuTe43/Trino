package tn.sncft.trino.circulation.geo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins {@link GeometrieLigne} to {@code backend/parite-geometrie.json}.
 *
 * <p>Half of a pair. {@code PariteGeometrieSimulateurTest} asserts the
 * simulator's {@code GeometrieCourse} against the same file, so the two
 * implementations cannot drift apart without one of them turning red -- and
 * neither module has to depend on the other to get that (see {@link
 * FixtureParite}).
 *
 * <p>Why it matters: the API projects a GPS fix onto a chainage and the
 * simulator turns a chainage back into a fix. If the two disagree, a train is
 * drawn beside the track rather than on it, every number the API reports about
 * it is consistent with itself, and nothing anywhere logs a word. The failure
 * mode is a demo where the trains are visibly in the sea.
 *
 * <p>The fixture is the real seeded ligne 4 -- 41 trace points, 6 stops, 25 km
 * -- rather than a synthetic straight line, because the interpolation being
 * tested is between stop anchors and a straight line makes anchoring and
 * arc-length agree by accident.
 */
class PariteGeometrieApiTest {

    @Test
    @DisplayName("GeometrieLigne reproduit les positions du fixture de parité")
    void positionsConformesAuFixture() throws Exception {
        JsonNode fixture = new ObjectMapper().readTree(FixtureParite.fichier());

        List<List<Double>> trace = new ArrayList<>();
        for (JsonNode point : fixture.get("trace")) {
            trace.add(List.of(point.get(0).asDouble(), point.get(1).asDouble()));
        }
        List<GeometrieLigne.Arret> arrets = new ArrayList<>();
        for (JsonNode arret : fixture.get("arrets")) {
            arrets.add(new GeometrieLigne.Arret(arret.get("pkKm").asDouble(),
                    arret.get("latitude").asDouble(), arret.get("longitude").asDouble()));
        }

        GeometrieLigne geometrie = GeometrieLigne.depuis(trace, arrets);

        assertEquals(fixture.get("longueurTotale").asDouble(), geometrie.longueurTotale(),
                FixtureParite.TOLERANCE, "longueur totale");

        for (JsonNode attendu : fixture.get("attendus")) {
            double pk = attendu.get("pkKm").asDouble();
            GeometrieLigne.PointGeo point = geometrie.positionA(pk);
            assertEquals(attendu.get("latitude").asDouble(), point.latitude(),
                    FixtureParite.TOLERANCE, "latitude à pk=" + pk);
            assertEquals(attendu.get("longitude").asDouble(), point.longitude(),
                    FixtureParite.TOLERANCE, "longitude à pk=" + pk);
        }
    }

    @Test
    @DisplayName("projeter est l'inverse de positionA sur toute la desserte")
    void projectionInversePosition() throws Exception {
        JsonNode fixture = new ObjectMapper().readTree(FixtureParite.fichier());

        List<List<Double>> trace = new ArrayList<>();
        for (JsonNode point : fixture.get("trace")) {
            trace.add(List.of(point.get(0).asDouble(), point.get(1).asDouble()));
        }
        List<GeometrieLigne.Arret> arrets = new ArrayList<>();
        for (JsonNode arret : fixture.get("arrets")) {
            arrets.add(new GeometrieLigne.Arret(arret.get("pkKm").asDouble(),
                    arret.get("latitude").asDouble(), arret.get("longitude").asDouble()));
        }
        GeometrieLigne geometrie = GeometrieLigne.depuis(trace, arrets);

        // Only the samples inside the served range: positionA clamps outside it,
        // so projecting the clamped point back necessarily returns the endpoint
        // rather than the chainage that was asked for. That is the documented
        // behaviour, not a round-trip failure.
        for (JsonNode attendu : fixture.get("attendus")) {
            double pk = attendu.get("pkKm").asDouble();
            if (pk < 0 || pk > fixture.get("longueurTotale").asDouble()) {
                continue;
            }
            GeometrieLigne.PointGeo point = geometrie.positionA(pk);
            // 10 m. The projection walks the polyline and the position
            // interpolates between anchors, so the round trip is exact only
            // where the two scales coincide -- at the stops.
            assertEquals(pk, geometrie.projeter(point.latitude(), point.longitude()), 0.01,
                    "aller-retour du chaînage à pk=" + pk);
        }
    }
}
