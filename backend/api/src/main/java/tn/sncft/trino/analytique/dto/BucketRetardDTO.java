package tn.sncft.trino.analytique.dto;

import tn.sncft.trino.circulation.domaine.ClasseRetard;

/**
 * One bar of the delay histogram: how many courses fell in a delay bucket over
 * the selected range.
 *
 * <p>The bucket is computed by {@link ClasseRetard#de(int)}, not by a CASE in
 * SQL. The thresholds already exist in two places (that enum and the frontend's
 * {@code couleurs.ts}, which mirrors it deliberately); a third copy in a query
 * is the one that would drift silently, because nothing would fail -- the bars
 * would just stop matching the colours next to them.
 */
public record BucketRetardDTO(ClasseRetard classe, long courses) {
}
