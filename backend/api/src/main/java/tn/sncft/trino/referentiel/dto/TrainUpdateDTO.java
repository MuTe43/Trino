package tn.sncft.trino.referentiel.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tn.sncft.trino.referentiel.domaine.TypeTrain;

/**
 * Request DTO for updating a train.
 */
public record TrainUpdateDTO(
        @NotBlank @Size(max = 20) String numero,
        @Size(max = 120) String nom,
        @NotNull TypeTrain type,
        Long ligneId,
        Short capacite,
        Short vitesseMaxKmh,
        Boolean actif
) {
}
