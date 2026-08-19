package tn.sncft.trino.analytique.dto;

/**
 * One gare's delay profile over a range.
 *
 * <p>The same measurement the heatmap makes, collapsed over the hour dimension:
 * the heatmap answers "when is this station late", this answers "how late is
 * this station". Kept as its own type rather than reusing {@link CaseHeatmapDTO}
 * with a sentinel hour, which would put a value in that field that means "no
 * hour" and leave every consumer to know it.
 *
 * <p>{@code region} rides along because it is the axis a supervisor groups by
 * once the export is open in a spreadsheet, and it costs one already-indexed
 * column on a join that is being made anyway.
 */
public record RetardParGareDTO(
        Long gareId,
        String gareNom,
        String region,
        long passages,
        long passagesEnRetard,
        double retardMoyenMin,
        int retardMaxMin) {
}
