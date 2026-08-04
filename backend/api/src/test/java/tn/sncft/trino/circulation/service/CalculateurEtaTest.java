package tn.sncft.trino.circulation.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tn.sncft.trino.circulation.domaine.Course;
import tn.sncft.trino.circulation.domaine.PassageGare;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CalculateurEtaTest {

    private final CalculateurEta calculateur = new CalculateurEta();

    /** A window of fixes {@code minutes} apart, walking from 0 to {@code avancementKm}. */
    private EtatCirculation etat(double avancementKm, int minutes, short vitesseAffichee) {
        FixPosition ancien = new FixPosition(
                FixtureCourse.DEPART, BigDecimal.ZERO, BigDecimal.ZERO, vitesseAffichee, BigDecimal.ZERO);
        FixPosition recent = new FixPosition(
                FixtureCourse.DEPART.plusMinutes(minutes), BigDecimal.ZERO, BigDecimal.ZERO,
                vitesseAffichee, BigDecimal.valueOf(avancementKm));
        return new EtatCirculation(1L, recent, List.of(ancien, recent));
    }

    @Test
    @DisplayName("la vitesse vient du chaînage, jamais du vitesseKmh du ping")
    void laVitesseVientDuChainage() {
        Course course = FixtureCourse.course();
        List<PassageGare> passages = FixtureCourse.passages(course);

        // 30 chainage km in 30 minutes is 60 km/h. The ping claims 200 km/h --
        // a ground speed against a longer polyline, and the number that would
        // silently make every ETA optimistic if it were used here.
        EtatCirculation etat = etat(30, 30, (short) 200);

        assertEquals(60.0, calculateur.vitesseChainageKmh(course, passages, etat), 1e-6);
    }

    @Test
    @DisplayName("l'ETA découle de la vitesse de chaînage")
    void lEtaDecouleDeLaVitesseDeChainage() {
        Course course = FixtureCourse.course();
        List<PassageGare> passages = FixtureCourse.passages(course);

        // At pk 30 doing 60 chainage km/h, the stop at pk 50 is 20 minutes away.
        EtatCirculation etat = etat(30, 30, (short) 200);
        OffsetDateTime eta = calculateur.pour(course, passages, etat);

        assertEquals(FixtureCourse.DEPART.plusMinutes(50), eta);

        // Had the ping's 200 km/h been used, the answer would have been 6
        // minutes out -- plausible enough that nobody would ever notice.
        assertTrue(Duration.between(FixtureCourse.DEPART.plusMinutes(36), eta).toMinutes() > 10);
    }

    @Test
    @DisplayName("l'ETA ne descend jamais sous l'heure théorique")
    void lEtaEstPlancheeParLHeureTheorique() {
        Course course = FixtureCourse.course();
        List<PassageGare> passages = FixtureCourse.passages(course);

        // 45 km in 15 minutes is 180 km/h: the remaining 5 km would take 100
        // seconds, putting the train at pk 50 well before its 06:30 slot.
        EtatCirculation etat = etat(45, 15, (short) 180);

        assertEquals(FixtureCourse.parOrdre(passages, 2).getArriveeTheorique(),
                calculateur.pour(course, passages, etat));
    }

    @Test
    @DisplayName("un seul ping retombe sur l'allure théorique du segment")
    void unSeulPingRetombeSurLAllureTheorique() {
        Course course = FixtureCourse.course();
        List<PassageGare> passages = FixtureCourse.passages(course);

        FixPosition unique = new FixPosition(FixtureCourse.DEPART.plusMinutes(10),
                BigDecimal.ZERO, BigDecimal.ZERO, (short) 90, BigDecimal.valueOf(10));
        EtatCirculation etat = new EtatCirculation(1L, unique, List.of(unique));

        // Segment 1 -> 2 is 50 km in 30 minutes: 100 km/h.
        assertEquals(100.0, calculateur.vitesseChainageKmh(course, passages, etat), 1e-6);
    }

    @Test
    @DisplayName("un train à l'arrêt retombe sur l'allure théorique au lieu d'un ETA infini")
    void unTrainImmobileRetombeSurLAllureTheorique() {
        Course course = FixtureCourse.course();
        List<PassageGare> passages = FixtureCourse.passages(course);

        FixPosition arrete = new FixPosition(FixtureCourse.DEPART.plusMinutes(20),
                BigDecimal.ZERO, BigDecimal.ZERO, (short) 0, BigDecimal.valueOf(20));
        FixPosition toujoursArrete = new FixPosition(FixtureCourse.DEPART.plusMinutes(25),
                BigDecimal.ZERO, BigDecimal.ZERO, (short) 0, BigDecimal.valueOf(20));
        EtatCirculation etat = new EtatCirculation(1L, toujoursArrete, List.of(arrete, toujoursArrete));

        assertEquals(100.0, calculateur.vitesseChainageKmh(course, passages, etat), 1e-6);
    }

    @Test
    @DisplayName("au terminus il n'y a plus d'arrêt suivant, donc pas d'ETA")
    void pasDEtaAuTerminus() {
        Course course = FixtureCourse.course();
        List<PassageGare> passages = FixtureCourse.passages(course);

        assertNull(calculateur.pour(course, passages, etat(150, 94, (short) 90)));
    }
}
