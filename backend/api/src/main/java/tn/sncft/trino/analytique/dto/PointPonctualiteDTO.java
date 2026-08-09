package tn.sncft.trino.analytique.dto;

import java.time.LocalDate;

/**
 * One point on the punctuality curve: a day or a month, depending on the
 * requested granularity.
 *
 * @param periode      first day of the bucket
 * @param tauxPonctualite share of stops reached under five minutes late, in [0, 1]
 */
public record PointPonctualiteDTO(
        LocalDate periode,
        long passages,
        long passagesPonctuels,
        double tauxPonctualite,
        double retardMoyenMin) {
}
