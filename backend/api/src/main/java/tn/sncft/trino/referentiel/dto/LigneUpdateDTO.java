package tn.sncft.trino.referentiel.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * Request DTO for updating a ligne.
 */
public record LigneUpdateDTO(
        @NotBlank @Size(max = 20) String code,
        @NotBlank @Size(max = 160) String nom,
        BigDecimal distanceKm,
        Short vitesseMaxKmh,
        Short tempsTheoriqueMin,
        List<List<Double>> trace,
        Boolean actif
) {
}
