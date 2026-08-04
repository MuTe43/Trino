package tn.sncft.trino.simulateur.dto;

import java.time.OffsetDateTime;

/** One position fix, shaped exactly as the ingestion endpoint expects it. */
public record PingDTO(
        Long courseId,
        OffsetDateTime horodatage,
        double latitude,
        double longitude,
        int vitesseKmh
) {
}
