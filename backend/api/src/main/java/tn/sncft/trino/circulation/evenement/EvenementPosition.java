package tn.sncft.trino.circulation.evenement;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * SSE {@code position} delta. One course moved; the client already holds the
 * snapshot it fetched over REST and patches this into it.
 *
 * <p>{@code vitesseKmh} is the ground speed straight off the ping, for display
 * only. The ETA next to it was computed from a chainage speed instead -- see
 * {@link tn.sncft.trino.circulation.service.CalculateurEta}.
 */
public record EvenementPosition(
        Long courseId,
        BigDecimal latitude,
        BigDecimal longitude,
        Short vitesseKmh,
        BigDecimal avancementKm,
        OffsetDateTime etaSuivante) {
}
