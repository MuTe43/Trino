package tn.sncft.trino.circulation.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tn.sncft.trino.circulation.domaine.CauseRetard;
import tn.sncft.trino.circulation.domaine.Course;
import tn.sncft.trino.circulation.domaine.PassageGare;
import tn.sncft.trino.circulation.domaine.StatutCourse;
import tn.sncft.trino.commun.ConflitException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The phase-6 additions to {@link MachineEtatCourse}: an agent's manual status
 * change and the delay cause an incident attributes.
 *
 * <p>Separate from {@link MachineEtatCourseTest}, which covers the transitions
 * derived from the feed. These are the ones a human causes.
 */
class ActionAgentMachineEtatCourseTest {

    private final MachineEtatCourse machine = new MachineEtatCourse();

    @Test
    @DisplayName("un agent peut annuler et poser un arrêt exceptionnel, rien d'autre")
    void seulesLesDeuxActionsSontAcceptees() {
        for (StatutCourse cible : StatutCourse.values()) {
            Course course = FixtureCourse.course();
            if (MachineEtatCourse.ACTIONS_AGENT.contains(cible)) {
                assertEquals(Optional.of(cible), machine.appliquerActionAgent(course, cible));
                assertEquals(cible, course.getStatut());
            } else {
                assertThrows(IllegalArgumentException.class,
                        () -> machine.appliquerActionAgent(course, cible), "statut " + cible);
            }
        }
    }

    /**
     * The gap the feed-side guard did not cover. ARRET_EXCEPTIONNEL is
     * deliberately non-terminal, so accepting it on a cancelled course meant the
     * next ping re-derived the run to EN_CIRCULATION -- a cancelled train back on
     * the passenger map, through the console rather than through the feed.
     */
    @Test
    @DisplayName("une course terminée ou annulée n'accepte plus aucune action d'agent")
    void courseTerminaleRefuseTouteAction() {
        for (StatutCourse terminal : MachineEtatCourse.TERMINAUX) {
            for (StatutCourse cible : MachineEtatCourse.ACTIONS_AGENT) {
                Course course = FixtureCourse.course();
                course.setStatut(terminal);

                assertThrows(ConflitException.class,
                        () -> machine.appliquerActionAgent(course, cible),
                        terminal + " -> " + cible);
                assertEquals(terminal, course.getStatut(), "le statut ne doit pas avoir bougé");
            }
        }
    }

    @Test
    @DisplayName("annuler puis poser un arrêt exceptionnel ne ressuscite pas la course")
    void annulationNeSeDefaitPasParLaConsole() {
        Course course = FixtureCourse.course();
        List<PassageGare> passages = FixtureCourse.passages(course);
        machine.appliquerActionAgent(course, StatutCourse.ANNULE);

        assertThrows(ConflitException.class,
                () -> machine.appliquerActionAgent(course, StatutCourse.ARRET_EXCEPTIONNEL));

        // And the feed still cannot move it either, which is the state the
        // refusal above protects.
        course.setDernierePositionAt(FixtureCourse.DEPART.plusMinutes(10));
        assertTrue(machine.evaluer(course, passages, FixtureCourse.DEPART.plusMinutes(10)).isEmpty());
        assertEquals(StatutCourse.ANNULE, course.getStatut());
    }

    @Test
    @DisplayName("reposer le statut déjà en place ne produit pas de transition")
    void memeStatutNeTransitePas() {
        // On ARRET_EXCEPTIONNEL, not ANNULE: a terminal course now refuses every
        // agent action outright, so re-stating ANNULE is a CONFLIT rather than a
        // no-op. This asserts the no-op path that remains.
        Course course = FixtureCourse.course();
        machine.appliquerActionAgent(course, StatutCourse.ARRET_EXCEPTIONNEL);

        assertTrue(machine.appliquerActionAgent(course, StatutCourse.ARRET_EXCEPTIONNEL).isEmpty(),
                "pas de delta SSE pour un statut inchangé");
    }

    @Test
    @DisplayName("ANNULE est terminal : le flux ne le défait pas")
    void annulationSurvitAuxPings() {
        Course course = FixtureCourse.course();
        List<PassageGare> passages = FixtureCourse.passages(course);
        machine.appliquerActionAgent(course, StatutCourse.ANNULE);

        course.setDernierePositionAt(FixtureCourse.DEPART.plusMinutes(1));
        assertTrue(machine.evaluer(course, passages, FixtureCourse.DEPART.plusMinutes(2)).isEmpty());
        assertEquals(StatutCourse.ANNULE, course.getStatut());
    }

    /**
     * The counterpart, and it is deliberate rather than an oversight: a manual
     * ARRET_EXCEPTIONNEL is cleared when the train moves again, which is exactly
     * the "ARRET_EXCEPTIONNEL --(ping resumes)--> previous state" transition of
     * the documented state machine. An agent flags a stop; the feed un-flags it
     * without anyone having to remember to.
     */
    @Test
    @DisplayName("un arrêt exceptionnel posé à la main est levé par la reprise des pings")
    void arretExceptionnelEstLeveParLeFlux() {
        Course course = FixtureCourse.course();
        List<PassageGare> passages = FixtureCourse.passages(course);
        machine.appliquerActionAgent(course, StatutCourse.ARRET_EXCEPTIONNEL);

        course.setDernierePositionAt(FixtureCourse.DEPART.plusMinutes(10));
        assertEquals(Optional.of(StatutCourse.EN_CIRCULATION),
                machine.evaluer(course, passages, FixtureCourse.DEPART.plusMinutes(10)));
    }

    @Test
    @DisplayName("la cause suggérée s'écrit sur une course qui n'en a pas")
    void causeSuggereeSEcritSiAbsente() {
        Course course = FixtureCourse.course();

        assertTrue(machine.suggererCause(course, CauseRetard.SIGNALISATION));
        assertEquals(CauseRetard.SIGNALISATION, course.getCauseRetard());
    }

    @Test
    @DisplayName("la cause suggérée n'écrase jamais celle qu'un agent a posée")
    void causeSuggereeNEcrasePas() {
        Course course = FixtureCourse.course();
        machine.attribuerCause(course, CauseRetard.ATTENTE_CORRESPONDANCE);

        assertFalse(machine.suggererCause(course, CauseRetard.SIGNALISATION));
        assertEquals(CauseRetard.ATTENTE_CORRESPONDANCE, course.getCauseRetard(),
                "un agent qui a nommé la cause en sait plus que la table de correspondance");
    }

    @Test
    @DisplayName("une cause nulle ne s'écrit pas")
    void causeNulleEstIgnoree() {
        Course course = FixtureCourse.course();

        assertFalse(machine.suggererCause(course, null));
        assertNull(course.getCauseRetard());
    }
}
