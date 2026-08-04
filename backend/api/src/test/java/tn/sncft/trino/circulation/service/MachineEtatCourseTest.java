package tn.sncft.trino.circulation.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tn.sncft.trino.circulation.domaine.Course;
import tn.sncft.trino.circulation.domaine.PassageGare;
import tn.sncft.trino.circulation.domaine.StatutCourse;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MachineEtatCourseTest {

    private final MachineEtatCourse machine = new MachineEtatCourse();

    private Optional<StatutCourse> evaluer(Course course, List<PassageGare> passages, int minutesApresDepart) {
        return machine.evaluer(course, passages, FixtureCourse.DEPART.plusMinutes(minutesApresDepart));
    }

    @Test
    @DisplayName("sans ping, 3 min après l'heure de départ, la course reste à quai")
    void resteAQuaiDansLaPeriodeDeGrace() {
        Course course = FixtureCourse.course();
        List<PassageGare> passages = FixtureCourse.passages(course);

        assertTrue(evaluer(course, passages, 3).isEmpty(), "aucune transition attendue");
        assertEquals(StatutCourse.A_QUAI, course.getStatut());
    }

    @Test
    @DisplayName("sans ping, 6 min après l'heure de départ, la course passe RETARDE")
    void passeRetardeQuandLeTrainNeSePresentePas() {
        Course course = FixtureCourse.course();
        List<PassageGare> passages = FixtureCourse.passages(course);

        // The transition nothing else can see: there is no ping to react to, so
        // without this a 06:00 departure whose trainset never arrives would
        // display as on time while the platform stands empty.
        assertEquals(Optional.of(StatutCourse.RETARDE), evaluer(course, passages, 6));
        assertEquals(StatutCourse.RETARDE, course.getStatut());
    }

    @Test
    @DisplayName("un premier ping fait passer la course en circulation")
    void premierPingMetEnCirculation() {
        Course course = FixtureCourse.course();
        List<PassageGare> passages = FixtureCourse.passages(course);
        course.setDernierePositionAt(FixtureCourse.DEPART.plusMinutes(1));

        assertEquals(Optional.of(StatutCourse.EN_CIRCULATION), evaluer(course, passages, 1));
    }

    @Test
    @DisplayName("au-delà de 5 minutes de retard une course en circulation devient RETARDE, et revient")
    void basculeEntreCirculationEtRetarde() {
        Course course = FixtureCourse.course();
        List<PassageGare> passages = FixtureCourse.passages(course);
        course.setStatut(StatutCourse.EN_CIRCULATION);
        course.setDernierePositionAt(FixtureCourse.DEPART.plusMinutes(40));
        course.setRetardMin(7);

        assertEquals(Optional.of(StatutCourse.RETARDE), evaluer(course, passages, 40));

        // Time made up on a padded segment: the course goes back.
        course.setRetardMin(2);
        assertEquals(Optional.of(StatutCourse.EN_CIRCULATION), evaluer(course, passages, 40));
    }

    @Test
    @DisplayName("plus de 90 s sans ping bascule en ARRET_EXCEPTIONNEL")
    void leSilenceBasculeEnArretExceptionnel() {
        Course course = FixtureCourse.course();
        List<PassageGare> passages = FixtureCourse.passages(course);
        course.setStatut(StatutCourse.EN_CIRCULATION);
        course.setDernierePositionAt(FixtureCourse.DEPART.plusMinutes(40));

        // 89 seconds is still within the window.
        assertTrue(machine.evaluer(course, passages,
                FixtureCourse.DEPART.plusMinutes(40).plusSeconds(89)).isEmpty());

        assertEquals(Optional.of(StatutCourse.ARRET_EXCEPTIONNEL), machine.evaluer(course, passages,
                FixtureCourse.DEPART.plusMinutes(40).plusSeconds(91)));
    }

    @Test
    @DisplayName("à la reprise du flux, l'état précédent est recalculé et non mémorisé")
    void lEtatPrecedentEstRecalculeALaReprise() {
        Course course = FixtureCourse.course();
        List<PassageGare> passages = FixtureCourse.passages(course);
        course.setStatut(StatutCourse.ARRET_EXCEPTIONNEL);
        course.setRetardMin(9);
        course.setDernierePositionAt(FixtureCourse.DEPART.plusMinutes(50));

        // Nothing stored what the course was before the silence; the delay is
        // enough to say what it is now.
        assertEquals(Optional.of(StatutCourse.RETARDE), evaluer(course, passages, 50));

        course.setStatut(StatutCourse.ARRET_EXCEPTIONNEL);
        course.setRetardMin(1);
        assertEquals(Optional.of(StatutCourse.EN_CIRCULATION), evaluer(course, passages, 50));
    }

    @Test
    @DisplayName("l'arrivée réelle au terminus clôt la course")
    void leTerminusClotLaCourse() {
        Course course = FixtureCourse.course();
        List<PassageGare> passages = FixtureCourse.passages(course);
        course.setStatut(StatutCourse.EN_CIRCULATION);
        course.setDernierePositionAt(FixtureCourse.DEPART.plusMinutes(94));
        FixtureCourse.parOrdre(passages, 4).setArriveeReelle(FixtureCourse.DEPART.plusMinutes(94));

        assertEquals(Optional.of(StatutCourse.TERMINUS_ATTEINT), evaluer(course, passages, 94));
    }

    @Test
    @DisplayName("les états terminaux ne sont jamais recalculés depuis le flux")
    void lesEtatsTerminauxNeSontPasRecalcules() {
        for (StatutCourse terminal : List.of(StatutCourse.ANNULE, StatutCourse.TERMINUS_ATTEINT)) {
            Course course = FixtureCourse.course();
            List<PassageGare> passages = FixtureCourse.passages(course);
            course.setStatut(terminal);
            // A late ping must not resurrect a run that is over.
            course.setDernierePositionAt(FixtureCourse.DEPART.plusMinutes(40));

            assertTrue(evaluer(course, passages, 40).isEmpty(), terminal + " ne doit pas changer");
            assertEquals(terminal, course.getStatut());
        }
    }

    @Test
    @DisplayName("un retard antérieur au départ n'empêche pas la reprise à l'arrivée du ping")
    void leRetardAvantDepartSeResoutAuPremierPing() {
        Course course = FixtureCourse.course();
        List<PassageGare> passages = FixtureCourse.passages(course);
        course.setStatut(StatutCourse.RETARDE);
        course.setRetardMin(12);

        OffsetDateTime arrivee = FixtureCourse.DEPART.plusMinutes(12);
        course.setDernierePositionAt(arrivee);

        // Still 12 minutes late, so it stays RETARDE -- but it is now running.
        assertTrue(machine.evaluer(course, passages, arrivee).isEmpty());
        assertEquals(StatutCourse.RETARDE, course.getStatut());
    }
}
