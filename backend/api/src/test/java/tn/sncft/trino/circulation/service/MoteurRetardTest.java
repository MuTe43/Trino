package tn.sncft.trino.circulation.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tn.sncft.trino.circulation.domaine.Course;
import tn.sncft.trino.circulation.domaine.PassageGare;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoteurRetardTest {

    private final MoteurRetard moteur = new MoteurRetard();

    @Test
    @DisplayName("l'arrivée réelle est horodatée par le ping qui franchit l'arrêt")
    void horodateLArriveeReelle() {
        Course course = FixtureCourse.course();
        List<PassageGare> passages = FixtureCourse.passages(course);

        // At pk 50 at 06:41 -- due 06:30, so 11 minutes late.
        moteur.traiter(course, passages, FixtureCourse.DEPART.plusMinutes(41), BigDecimal.valueOf(50));

        PassageGare arret = FixtureCourse.parOrdre(passages, 2);
        assertEquals(FixtureCourse.DEPART.plusMinutes(41), arret.getArriveeReelle());
        assertEquals(11, arret.getRetardMin());
        assertEquals(11, course.getRetardMin());
    }

    @Test
    @DisplayName("la marge absorbe le retard : 11 min à l'arrêt 2 deviennent 8 puis 6")
    void laMargeAbsorbeLeRetard() {
        Course course = FixtureCourse.course();
        List<PassageGare> passages = FixtureCourse.passages(course);

        moteur.traiter(course, passages, FixtureCourse.DEPART.plusMinutes(41), BigDecimal.valueOf(50));

        // Naive propagation would carry 11 all the way to the terminus. The
        // padding on the last two segments is 3 and 2 minutes.
        assertEquals(11, FixtureCourse.parOrdre(passages, 2).getRetardMin());
        assertEquals(8, FixtureCourse.parOrdre(passages, 3).getRetardMin());
        assertEquals(6, FixtureCourse.parOrdre(passages, 4).getRetardMin());
    }

    @Test
    @DisplayName("les estimées suivent le retard absorbé, pas le retard courant")
    void lesEstimeesSuiventLeRetardAbsorbe() {
        Course course = FixtureCourse.course();
        List<PassageGare> passages = FixtureCourse.passages(course);

        moteur.traiter(course, passages, FixtureCourse.DEPART.plusMinutes(41), BigDecimal.valueOf(50));

        PassageGare troisieme = FixtureCourse.parOrdre(passages, 3);
        assertEquals(troisieme.getArriveeTheorique().plusMinutes(8), troisieme.getArriveeEstimee());
        assertEquals(troisieme.getDepartTheorique().plusMinutes(8), troisieme.getDepartEstimee());

        PassageGare terminus = FixtureCourse.parOrdre(passages, 4);
        assertEquals(terminus.getArriveeTheorique().plusMinutes(6), terminus.getArriveeEstimee());
    }

    @Test
    @DisplayName("la marge ne rend jamais le retard négatif")
    void laMargeNeRendJamaisLeRetardNegatif() {
        Course course = FixtureCourse.course();
        List<PassageGare> passages = FixtureCourse.passages(course);

        // Two minutes late against three minutes of padding.
        moteur.traiter(course, passages, FixtureCourse.DEPART.plusMinutes(32), BigDecimal.valueOf(50));

        assertEquals(0, FixtureCourse.parOrdre(passages, 3).getRetardMin());
        assertEquals(0, FixtureCourse.parOrdre(passages, 4).getRetardMin());
        PassageGare troisieme = FixtureCourse.parOrdre(passages, 3);
        assertEquals(troisieme.getArriveeTheorique(), troisieme.getArriveeEstimee());
    }

    @Test
    @DisplayName("une estimée est gelée dès que l'heure réelle est connue")
    void geleLEstimeeUneFoisLArriveeConnue() {
        Course course = FixtureCourse.course();
        List<PassageGare> passages = FixtureCourse.passages(course);

        moteur.traiter(course, passages, FixtureCourse.DEPART.plusMinutes(41), BigDecimal.valueOf(50));
        PassageGare deuxieme = FixtureCourse.parOrdre(passages, 2);
        var estimeeGelee = deuxieme.getArriveeEstimee();
        var reelleGelee = deuxieme.getArriveeReelle();

        // A later ping, further along and later still: the passed stop must not move.
        moteur.traiter(course, passages, FixtureCourse.DEPART.plusMinutes(75), BigDecimal.valueOf(100));

        assertEquals(reelleGelee, deuxieme.getArriveeReelle());
        assertEquals(estimeeGelee, deuxieme.getArriveeEstimee());
        assertEquals(11, deuxieme.getRetardMin());
    }

    @Test
    @DisplayName("un trou dans le flux horodate tous les arrêts franchis, pas seulement le dernier")
    void horodateTousLesArretsFranchis() {
        Course course = FixtureCourse.course();
        List<PassageGare> passages = FixtureCourse.passages(course);

        // First fix of the run is already past the third stop: the feed dropped
        // everything in between. Leaving stop 2 null would keep it null all day
        // and stop the run from ever reaching TERMINUS_ATTEINT.
        moteur.traiter(course, passages, FixtureCourse.DEPART.plusMinutes(70), BigDecimal.valueOf(120));

        assertNotNull(FixtureCourse.parOrdre(passages, 2).getArriveeReelle());
        assertNotNull(FixtureCourse.parOrdre(passages, 3).getArriveeReelle());
        assertNull(FixtureCourse.parOrdre(passages, 4).getArriveeReelle());
    }

    @Test
    @DisplayName("l'origine n'a pas d'arrivée mais son départ réel est horodaté")
    void horodateLeDepartDeLOrigine() {
        Course course = FixtureCourse.course();
        List<PassageGare> passages = FixtureCourse.passages(course);
        PassageGare origine = FixtureCourse.parOrdre(passages, 1);

        // Still standing at pk 0: arrived nowhere, departed nowhere.
        moteur.traiter(course, passages, FixtureCourse.DEPART.plusMinutes(4), BigDecimal.ZERO);
        assertNull(origine.getDepartReelle());

        // Now moving: the origin's departure is what carries the start delay.
        moteur.traiter(course, passages, FixtureCourse.DEPART.plusMinutes(7), BigDecimal.valueOf(3));
        assertEquals(FixtureCourse.DEPART.plusMinutes(7), origine.getDepartReelle());
        assertNull(origine.getArriveeReelle());
        assertEquals(7, origine.getRetardMin());
    }

    @Test
    @DisplayName("seuls les passages dont l'estimée bouge sont renvoyés comme delta")
    void neRenvoieQueLesPassagesModifies() {
        Course course = FixtureCourse.course();
        List<PassageGare> passages = FixtureCourse.passages(course);

        MoteurRetard.ResultatRetard premier = moteur.traiter(
                course, passages, FixtureCourse.DEPART.plusMinutes(41), BigDecimal.valueOf(50));
        assertTrue(premier.franchissement());
        // Three, not two: stops 3 and 4 ahead, plus the stop the train has just
        // arrived at, whose departure estimate moved even though its arrival is
        // now frozen.
        assertEquals(3, premier.revises().size());

        // Same position, same delay, one second later: nothing downstream moved.
        MoteurRetard.ResultatRetard second = moteur.traiter(
                course, passages, FixtureCourse.DEPART.plusMinutes(41).plusSeconds(1), BigDecimal.valueOf(50));
        assertFalse(second.franchissement());
        assertTrue(second.revises().isEmpty(), "un delta vide ne doit pas être publié");
    }

    @Test
    @DisplayName("un train à quai garde une estimée de départ vivante tant qu'il n'est pas reparti")
    void leDepartEstimeeSuitTantQueLeTrainStationne() {
        Course course = FixtureCourse.course();
        List<PassageGare> passages = FixtureCourse.passages(course);
        PassageGare deuxieme = FixtureCourse.parOrdre(passages, 2);

        // Arrives at pk 50 eleven minutes late.
        moteur.traiter(course, passages, FixtureCourse.DEPART.plusMinutes(41), BigDecimal.valueOf(50));
        assertEquals(FixtureCourse.DEPART.plusMinutes(43), deuxieme.getDepartEstimee());

        // Still sitting there nine minutes later, having not departed. The
        // arrival is observed and frozen, but the departure estimate has to
        // keep moving or the station board loses the train it is holding.
        moteur.traiter(course, passages, FixtureCourse.DEPART.plusMinutes(50), BigDecimal.valueOf(50));

        assertEquals(FixtureCourse.DEPART.plusMinutes(41), deuxieme.getArriveeReelle(),
                "l'arrivée réelle ne bouge pas");
        assertEquals(11, deuxieme.getRetardMin(), "le retard observé à l'arrêt ne bouge pas");
        assertNull(deuxieme.getDepartReelle());
        assertEquals(FixtureCourse.DEPART.plusMinutes(43), deuxieme.getDepartEstimee(),
                "le départ estimé reste calculé, pas gelé");
    }

    @Test
    @DisplayName("une fois le départ réel horodaté, l'estimée de départ ne bouge plus")
    void leDepartEstimeeGeleUneFoisLeDepartReel() {
        Course course = FixtureCourse.course();
        List<PassageGare> passages = FixtureCourse.passages(course);
        PassageGare deuxieme = FixtureCourse.parOrdre(passages, 2);

        moteur.traiter(course, passages, FixtureCourse.DEPART.plusMinutes(41), BigDecimal.valueOf(50));
        moteur.traiter(course, passages, FixtureCourse.DEPART.plusMinutes(45), BigDecimal.valueOf(60));
        OffsetDateTime departReel = deuxieme.getDepartReelle();
        OffsetDateTime departEstime = deuxieme.getDepartEstimee();
        assertNotNull(departReel);

        moteur.traiter(course, passages, FixtureCourse.DEPART.plusMinutes(60), BigDecimal.valueOf(80));

        assertEquals(departReel, deuxieme.getDepartReelle());
        assertEquals(departEstime, deuxieme.getDepartEstimee());
    }

    @Test
    @DisplayName("l'arrondi du retard se fait à la minute la plus proche, et une avance reste négative")
    void arrondiEtAvance() {
        assertEquals(2, MoteurRetard.minutesEntre(
                FixtureCourse.DEPART, FixtureCourse.DEPART.plusSeconds(100)));
        assertEquals(-3, MoteurRetard.minutesEntre(
                FixtureCourse.DEPART, FixtureCourse.DEPART.minusMinutes(3)));
    }
}
