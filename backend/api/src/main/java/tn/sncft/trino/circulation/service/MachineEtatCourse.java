package tn.sncft.trino.circulation.service;

import org.springframework.stereotype.Component;
import tn.sncft.trino.circulation.domaine.Course;
import tn.sncft.trino.circulation.domaine.PassageGare;
import tn.sncft.trino.circulation.domaine.StatutCourse;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

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

    private StatutCourse calculer(Course course, List<PassageGare> passages, OffsetDateTime maintenant) {
        StatutCourse actuel = course.getStatut();

        // Terminal. ANNULE is an agent's decision and TERMINUS_ATTEINT is the
        // end of the run; neither is re-derived from the feed, or a late ping
        // would resurrect a finished course.
        if (actuel == StatutCourse.ANNULE || actuel == StatutCourse.TERMINUS_ATTEINT) {
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
