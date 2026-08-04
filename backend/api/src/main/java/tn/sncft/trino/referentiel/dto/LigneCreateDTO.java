package tn.sncft.trino.referentiel.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * Request DTO for creating a ligne. `trace` is the ordered polyline as a
 * JSON array of [lon,lat] pairs; parsing to/from the entity's jsonb string
 * happens in the service layer.
 *
 * <p>Same constraints as {@link LigneUpdateDTO}: a ligne created without a
 * usable polyline can never carry a course, and the failure would only surface
 * later, in the position feed.
 */
public record LigneCreateDTO(
        @NotBlank @Size(max = 20) String code,
        @NotBlank @Size(max = 160) String nom,
        BigDecimal distanceKm,
        Short vitesseMaxKmh,
        Short tempsTheoriqueMin,

        @NotNull(message = "obligatoire")
        @Size(min = 2, message = "au moins deux points sont requis")
        List<@NotNull(message = "point manquant")
             @Size(min = 2, max = 2, message = "chaque point doit être une paire [lon, lat]")
             List<@NotNull(message = "coordonnée manquante") Double>> trace,

        Boolean actif
) {
}
