package tn.sncft.trino.circulation.service;

import org.springframework.stereotype.Component;
import tn.sncft.trino.circulation.domaine.CauseRetard;
import tn.sncft.trino.circulation.domaine.Course;
import tn.sncft.trino.circulation.domaine.PassageGare;
import tn.sncft.trino.circulation.domaine.StatutCourse;
import tn.sncft.trino.commun.ConflitException;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The ONLY place {@code course.statut} is assigned. Every transition of the
 * state machine in docs/architecture/domain-model.md is decided here, so there
 * is exactly one answer to "why is this train showing as delayed".
 *
 * <p>Callers that detect a condition -- ingestion seeing a ping,
 * {@link DetecteurSilence} seeing none -- pass the facts in and let this class
 * decide. They never set a status themselves.
 */
@Component
public class MachineEtatCourse {

    /** No ping for longer than this and the run is no longer being observed. */
    public static final Duration SILENCE_MAX = Duration.ofSeconds(90);

    /** Grace after the scheduled departure before a no-show counts as late. */
    public static final Duration RETARD_AVANT_DEPART = Duration.ofMinutes(5);

    /** At or above this many minutes a running course reads as RETARDE. */
    public static final int SEUIL_RETARD_MIN = 5;

    /**
     * The only statuses an agent may set by hand (phase 6). Everything else is
     * derived from the feed and cannot be typed in.
     */
    public static final Set<StatutCourse> ACTIONS_AGENT =
            EnumSet.of(StatutCourse.ARRET_EXCEPTIONNEL, StatutCourse.ANNULE);

    /**
     * A run that is over. Neither is re-derived from the feed, and neither
     * accepts an agent action either -- one definition, used by both paths.
     */
    public static final Set<StatutCourse> TERMINAUX =
            EnumSet.of(StatutCourse.ANNULE, StatutCourse.TERMINUS_ATTEINT);

    /**
     * Recomputes the status and assigns it.
     *
     * @return the new status if it changed, empty if it did not -- so callers
     *         emit a {@code statut} event only on an actual transition
     */
    public Optional<StatutCourse> evaluer(Course course, List<PassageGare> passages, OffsetDateTime maintenant) {
        StatutCourse actuel = course.getStatut();
        StatutCourse cible = calculer(course, passages, maintenant);
        if (cible == actuel) {
            return Optional.empty();
        }
        course.setStatut(cible);
        return Optional.of(cible);
    }

    /**
     * An agent's manual status change, from the exploitation console.
     *
     * <p>Only the two statuses phase 6 grants an agent: {@code ANNULE} and
     * {@code ARRET_EXCEPTIONNEL}. It goes through this class for the same reason
     * every other transition does -- a manual cancellation is still a status
     * change, and letting a service write {@code statut} directly is how a
     * second, disagreeing answer to "why is this train showing as cancelled"
     * gets into the system.
     *
     * <p>{@code ANNULE} is terminal, so the feed can never undo it. A manual
     * {@code ARRET_EXCEPTIONNEL} is deliberately NOT terminal: it is re-derived
     * away as soon as pings resume, which is exactly the
     * "ARRET_EXCEPTIONNEL --(ping resumes)--> previous state" transition of the
     * documented state machine. An agent flags a stop; the train moving again
     * un-flags it without anyone having to remember to.
     *
     * <p>A course already in a terminal state accepts nothing. Without that
     * check, {@code ANNULE -> ARRET_EXCEPTIONNEL} was accepted -- and since
     * ARRET_EXCEPTIONNEL is deliberately non-terminal, the very next ping
     * re-derived the course to EN_CIRCULATION. A cancelled train reappeared as
     * running on the passenger map, which is the failure "ANNULE is terminal"
     * exists to prevent. Guarding only {@code evaluer} guarded the feed and left
     * the console as a way in.
     *
     * @return the new status if it changed, empty if the course was already there
     * @throws IllegalArgumentException if asked for a status no agent may set --
     *         a programming error, not a user error: the caller validates first
     * @throws ConflitException if the run is already over
     */
    public Optional<StatutCourse> appliquerActionAgent(Course course, StatutCourse cible) {
        if (!ACTIONS_AGENT.contains(cible)) {
            throw new IllegalArgumentException("Statut hors du pouvoir d'un agent : " + cible);
        }
        if (TERMINAUX.contains(course.getStatut())) {
            throw new ConflitException("La course " + course.getId() + " est " + course.getStatut()
                    + " : une action d'agent ne s'applique plus.");
        }
        if (course.getStatut() == cible) {
            return Optional.empty();
        }
        course.setStatut(cible);
        return Optional.of(cible);
    }

    /**
     * Attributes a delay cause, without ever overwriting one already set.
     *
     * <p>Here rather than on the caller so the "suggests, never overwrites" rule
     * has one implementation: an incident type maps to a cause, but an agent who
     * typed the cause in knows more than the lookup table does.
     *
     * @return true if the cause was written
     */
    public boolean suggererCause(Course course, CauseRetard cause) {
        if (cause == null || course.getCauseRetard() != null) {
            return false;
        }
        course.setCauseRetard(cause);
        return true;
    }

    /** Overwrites the delay cause. The agent named it explicitly, so it wins. */
    public void attribuerCause(Course course, CauseRetard cause) {
        course.setCauseRetard(cause);
    }

    private StatutCourse calculer(Course course, List<PassageGare> passages, OffsetDateTime maintenant) {
        StatutCourse actuel = course.getStatut();

        // Terminal. ANNULE is an agent's decision and TERMINUS_ATTEINT is the
        // end of the run; neither is re-derived from the feed, or a late ping
        // would resurrect a finished course.
        if (TERMINAUX.contains(actuel)) {
            return actuel;
        }

        PassageGare terminus = passages.stream().max(Comparator.comparing(PassageGare::getOrdre)).orElse(null);
        if (terminus != null && terminus.getArriveeReelle() != null) {
            return StatutCourse.TERMINUS_ATTEINT;
        }

        OffsetDateTime dernierPing = course.getDernierePositionAt();
        if (dernierPing == null) {
            // Nothing has ever been heard from this trainset. Without this
            // branch a 14:00 departure whose train never arrives displays as on
            // time while the platform stands empty -- the most visible failure
            // a passenger can catch us in. DetecteurSilence is what calls it.
            return maintenant.isAfter(course.getDepartTheorique().plus(RETARD_AVANT_DEPART))
                    ? StatutCourse.RETARDE
                    : StatutCourse.A_QUAI;
        }

        if (Duration.between(dernierPing, maintenant).compareTo(SILENCE_MAX) > 0) {
            return StatutCourse.ARRET_EXCEPTIONNEL;
        }

        // The feed is live. This one line also implements
        // "ARRET_EXCEPTIONNEL --(ping resumes)--> previous state": the previous
        // state is not stored anywhere, it is re-derived, which is exactly what
        // the two middle transitions of the state machine already say.
        return course.getRetardMin() >= SEUIL_RETARD_MIN
                ? StatutCourse.RETARDE
                : StatutCourse.EN_CIRCULATION;
    }
}
