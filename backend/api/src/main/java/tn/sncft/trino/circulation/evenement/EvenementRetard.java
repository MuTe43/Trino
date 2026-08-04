package tn.sncft.trino.circulation.evenement;

import tn.sncft.trino.circulation.domaine.CauseRetard;
import tn.sncft.trino.circulation.domaine.ClasseRetard;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * SSE {@code retard} delta.
 *
 * <p>{@code passagesRevises} carries only the stops whose estimate actually
 * moved, so a station board can update its own row without refetching the
 * course. Still a delta -- never the full passage list.
 */
public record EvenementRetard(
        Long courseId,
        int retardMin,
        ClasseRetard classeRetard,
        CauseRetard causeRetard,
        List<PassageRevise> passagesRevises) {

    public record PassageRevise(
            Long gareId,
            short ordre,
            OffsetDateTime arriveeEstimee,
            OffsetDateTime departEstime,
            int retardMin) {
    }
}
