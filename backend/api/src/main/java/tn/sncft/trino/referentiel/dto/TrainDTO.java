package tn.sncft.trino.referentiel.dto;

import tn.sncft.trino.referentiel.domaine.TypeTrain;

/**
 * Response DTO for a train (rolling stock). No status, no delay: those
 * belong on Course.
 */
public record TrainDTO(
        Long id,
        String numero,
        String nom,
        TypeTrain type,
        Long ligneId,
        Short capacite,
        Short vitesseMaxKmh,
        boolean actif
) {
}
