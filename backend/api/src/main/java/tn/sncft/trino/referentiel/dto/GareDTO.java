package tn.sncft.trino.referentiel.dto;

import java.math.BigDecimal;

/**
 * Response DTO for a gare.
 */
public record GareDTO(
        Long id,
        String code,
        String nom,
        String region,
        BigDecimal latitude,
        BigDecimal longitude,
        Short nbQuais,
        String responsable,
        boolean actif
) {
}
