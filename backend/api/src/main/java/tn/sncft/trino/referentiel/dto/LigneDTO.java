package tn.sncft.trino.referentiel.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Response DTO for a ligne. `trace` is exposed as a JSON array of [lon,lat]
 * pairs, parsed from the entity's raw jsonb string by the service layer.
 */
public record LigneDTO(
        Long id,
        String code,
        String nom,
        BigDecimal distanceKm,
        Short vitesseMaxKmh,
        Short tempsTheoriqueMin,
        List<List<Double>> trace,
        boolean actif
) {
}
