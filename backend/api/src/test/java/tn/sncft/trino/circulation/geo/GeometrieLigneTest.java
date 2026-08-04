package tn.sncft.trino.circulation.geo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeometrieLigneTest {

    /**
     * A straight north-south line: 11 vertices from 36.0N to 35.0N along the
     * same meridian, roughly 111 km of polyline. The stops are declared at
     * chainages 0 / 30 / 60, so the two scales deliberately disagree by nearly
     * a factor of two -- the situation the anchoring exists to survive.
     */
    private static List<List<Double>> traceDroite() {
        List<List<Double>> trace = new ArrayList<>();
        for (int i = 0; i <= 10; i++) {
            trace.add(List.of(10.0, 36.0 - i * 0.1));
        }
        return trace;
    }

    private static GeometrieLigne ligneDroite() {
        return GeometrieLigne.depuis(traceDroite(), List.of(
                new GeometrieLigne.Arret(0.0, 36.0, 10.0),
                new GeometrieLigne.Arret(30.0, 35.5, 10.0),
                new GeometrieLigne.Arret(60.0, 35.0, 10.0)));
    }

    @Test
    @DisplayName("la longueur est la chaîne kilométrique, pas la longueur de la polyligne")
    void longueurTotaleEstLaChainage() {
        GeometrieLigne geometrie = ligneDroite();

        assertEquals(60.0, geometrie.longueurTotale(), 1e-9);
        // The drawn polyline really is about 111 km. Conflating the two is the
        // bug this class exists to prevent, so assert they differ.
        assertTrue(geometrie.longueurPolyligne() > 105.0,
                "la polyligne mesure environ 111 km : " + geometrie.longueurPolyligne());
    }

    @Test
    @DisplayName("un train au pk d'un arrêt est exactement sur cet arrêt")
    void positionAUnArretTombeSurLArret() {
        GeometrieLigne geometrie = ligneDroite();

        assertEquals(36.0, geometrie.positionA(0.0).latitude(), 1e-6);
        assertEquals(35.5, geometrie.positionA(30.0).latitude(), 1e-6);
        assertEquals(35.0, geometrie.positionA(60.0).latitude(), 1e-6);
    }

    @Test
    @DisplayName("entre deux arrêts la position est interpolée proportionnellement")
    void positionInterpoleeEntreArrets() {
        GeometrieLigne geometrie = ligneDroite();

        assertEquals(35.75, geometrie.positionA(15.0).latitude(), 1e-6);
        assertEquals(35.25, geometrie.positionA(45.0).latitude(), 1e-6);
    }

    @Test
    @DisplayName("des arrêts inégalement espacés restent ancrés chacun sur le sien")
    void ancrageParSegmentEtNonGlobal() {
        // Middle stop at chainage 10 of 60, but geometrically halfway along the
        // line. A single global scale factor would put it at latitude ~35.9;
        // anchoring per segment keeps it where the gare actually is.
        GeometrieLigne geometrie = GeometrieLigne.depuis(traceDroite(), List.of(
                new GeometrieLigne.Arret(0.0, 36.0, 10.0),
                new GeometrieLigne.Arret(10.0, 35.5, 10.0),
                new GeometrieLigne.Arret(60.0, 35.0, 10.0)));

        assertEquals(35.5, geometrie.positionA(10.0).latitude(), 1e-6);
        assertEquals(35.75, geometrie.positionA(5.0).latitude(), 1e-6);
        assertEquals(35.25, geometrie.positionA(35.0).latitude(), 1e-6);
    }

    @Test
    @DisplayName("la position est bornée à la ligne desservie")
    void positionEstBornee() {
        GeometrieLigne geometrie = ligneDroite();

        assertEquals(36.0, geometrie.positionA(-25.0).latitude(), 1e-6);
        assertEquals(35.0, geometrie.positionA(999.0).latitude(), 1e-6);
    }

    @Test
    @DisplayName("l'avancement progresse de façon monotone avec le pk")
    void avancementMonotone() {
        GeometrieLigne geometrie = ligneDroite();

        double precedente = 91.0;
        for (double pk = 0; pk <= 60; pk += 2.5) {
            double latitude = geometrie.positionA(pk).latitude();
            assertTrue(latitude < precedente, "latitude non décroissante au pk " + pk);
            precedente = latitude;
        }
    }

    @Test
    @DisplayName("projeter est l'inverse de positionA : un point GPS redonne son pk")
    void projeterInversePositionA() {
        GeometrieLigne geometrie = ligneDroite();

        for (double pk : new double[]{0.0, 7.5, 30.0, 42.0, 60.0}) {
            GeometrieLigne.PointGeo point = geometrie.positionA(pk);
            assertEquals(pk, geometrie.projeter(point.latitude(), point.longitude()), 0.05,
                    "aller-retour pk -> position -> pk au pk " + pk);
        }
    }

    @Test
    @DisplayName("un RETOUR sur le tracé inversé retrouve les mêmes gares")
    void sensRetourSurTraceInversee() {
        // A RETOUR course gets its stops mirrored to ascending pk, but the
        // trace is stored once in the ALLER direction. Walking it unreversed
        // makes the anchors run backwards -- caught by the monotonicity guard
        // rather than silently placing trains at the wrong end of the line.
        List<List<Double>> aller = traceDroite();
        assertThrows(IllegalStateException.class,
                () -> GeometrieLigne.depuis(aller, List.of(
                        new GeometrieLigne.Arret(0.0, 35.0, 10.0),
                        new GeometrieLigne.Arret(30.0, 35.5, 10.0),
                        new GeometrieLigne.Arret(60.0, 36.0, 10.0))));

        List<List<Double>> retour = new ArrayList<>(aller);
        java.util.Collections.reverse(retour);
        GeometrieLigne geometrie = GeometrieLigne.depuis(retour, List.of(
                new GeometrieLigne.Arret(0.0, 35.0, 10.0),
                new GeometrieLigne.Arret(30.0, 35.5, 10.0),
                new GeometrieLigne.Arret(60.0, 36.0, 10.0)));

        assertEquals(35.0, geometrie.positionA(0.0).latitude(), 1e-6);
        assertEquals(35.5, geometrie.positionA(30.0).latitude(), 1e-6);
        assertEquals(36.0, geometrie.positionA(60.0).latitude(), 1e-6);
    }

    @Test
    @DisplayName("un arrêt loin du tracé est refusé plutôt que silencieusement ancré")
    void arretHorsTraceRefuse() {
        IllegalStateException erreur = assertThrows(IllegalStateException.class,
                () -> GeometrieLigne.depuis(traceDroite(), List.of(
                        new GeometrieLigne.Arret(0.0, 36.0, 10.0),
                        // 100 km east of the line
                        new GeometrieLigne.Arret(30.0, 35.5, 11.1),
                        new GeometrieLigne.Arret(60.0, 35.0, 10.0))));

        assertTrue(erreur.getMessage().contains("divergé"), erreur.getMessage());
    }

    @Test
    @DisplayName("des arrêts non monotones le long du tracé sont refusés")
    void arretsNonMonotonesRefuses() {
        // pk ascends but the geometry runs backwards: the stop at pk 30 sits
        // further along the polyline than the stop at pk 60.
        assertThrows(IllegalStateException.class,
                () -> GeometrieLigne.depuis(traceDroite(), List.of(
                        new GeometrieLigne.Arret(0.0, 36.0, 10.0),
                        new GeometrieLigne.Arret(30.0, 35.2, 10.0),
                        new GeometrieLigne.Arret(60.0, 35.6, 10.0))));
    }

    @Test
    @DisplayName("un tracé ou une desserte dégénérés sont refusés")
    void entreesDegenereesRefusees() {
        assertThrows(IllegalArgumentException.class,
                () -> GeometrieLigne.depuis(List.of(List.of(10.0, 36.0)), List.of(
                        new GeometrieLigne.Arret(0.0, 36.0, 10.0),
                        new GeometrieLigne.Arret(60.0, 35.0, 10.0))));

        assertThrows(IllegalArgumentException.class,
                () -> GeometrieLigne.depuis(traceDroite(),
                        List.of(new GeometrieLigne.Arret(0.0, 36.0, 10.0))));
    }

    @Test
    @DisplayName("haversine donne des distances plausibles")
    void haversinePlausible() {
        // Tunis -> Sousse, about 125 km as the crow flies.
        double km = GeometrieLigne.haversineKm(36.7975, 10.1839, 35.8256, 10.6084);
        assertEquals(115.0, km, 15.0);

        assertEquals(0.0, GeometrieLigne.haversineKm(36.0, 10.0, 36.0, 10.0), 1e-9);
    }
}
