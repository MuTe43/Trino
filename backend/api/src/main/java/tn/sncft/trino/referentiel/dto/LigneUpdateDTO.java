package tn.sncft.trino.referentiel.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * Request DTO for updating a ligne.
 *
 * <p>PUT replaces the whole resource, so an omitted {@code trace} used to
 * write NULL and silently destroy the polyline every later phase positions
 * trains against — the request still returned 200. It is required here, and
 * required to be a usable polyline: at least two points, each of them exactly
 * a [lon, lat] pair. Rejecting at the boundary is the only place this costs
 * nothing; by the time it reaches GeometrieLigne the data is already stored.
 */
public record LigneUpdateDTO(
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
