package tn.sncft.trino.referentiel.dto;

import java.math.BigDecimal;

/**
 * Response DTO for one ordered stop of a ligne's desserte.
 */
public record DesserteDTO(
        Long id,
        Long ligneId,
        Long gareId,
        String gareNom,
        Short ordre,
        BigDecimal pkKm,
        Short offsetArriveeMin,
        Short offsetDepartMin
) {
}
