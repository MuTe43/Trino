package tn.sncft.trino.circulation.evenement;

import tn.sncft.trino.circulation.domaine.CauseRetard;
import tn.sncft.trino.circulation.domaine.ClasseRetard;
import tn.sncft.trino.circulation.domaine.StatutCourse;

/**
 * SSE {@code statut} delta, emitted only when the state machine actually
 * transitions -- never on every ping.
 */
public record EvenementStatut(
        Long courseId,
        StatutCourse statut,
        int retardMin,
        ClasseRetard classeRetard,
        CauseRetard causeRetard) {
}
