package tn.sncft.trino.referentiel.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Request DTO for creating a gare.
 */
public record GareCreateDTO(
        @NotBlank @Size(max = 10) String code,
        @NotBlank @Size(max = 120) String nom,
        @Size(max = 80) String region,
        @NotNull BigDecimal latitude,
        @NotNull BigDecimal longitude,
        Short nbQuais,
        @Size(max = 120) String responsable,
        Boolean actif
) {
}
