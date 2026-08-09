package tn.sncft.trino.analytique.dto;

/**
 * One cell of the gare x hour grid.
 *
 * @param heure hour of the day in {@code Africa/Tunis}, not UTC. Times are
 *              stored as timestamptz in UTC (invariant 6); bucketing them
 *              without converting first would shift every row by the offset and
 *              put the morning peak in the wrong column.
 */
public record CaseHeatmapDTO(
        Long gareId,
        String gareNom,
        int heure,
        double retardMoyenMin,
        long passages) {
}
