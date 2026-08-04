package tn.sncft.trino.circulation.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * One historical fix, for the trace replay on {@code /courses/{id}/positions}.
 * Read from {@code position_course}, which is history: the live position of a
 * running course comes from memory instead.
 *
 * <p>{@code vitesseKmh} is a ground speed, for display. {@code avancementKm} is
 * chainage. The two are in different units and must not be combined.
 */
public record PositionDTO(
        OffsetDateTime horodatage,
        BigDecimal latitude,
        BigDecimal longitude,
        Short vitesseKmh,
        BigDecimal avancementKm,
        Long garePrecedenteId,
        Long gareSuivanteId,
        OffsetDateTime etaSuivante) {
}
