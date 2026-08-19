package tn.sncft.trino.commun;

import java.time.LocalDate;

/**
 * The bounds every {@code du}/{@code au} endpoint accepts.
 *
 * <p>Shared rather than repeated: phase 6 added a second report with its own
 * copy of the null and ordering checks and no width limit, so
 * {@code /rapports/incidents?du=1900-01-01&au=2999-12-31} answered 200 while the
 * identical window on {@code /rapports/ponctualite} answered 400. Two endpoints
 * on the same screen disagreeing about what a legal range is.
 *
 * <p>{@link IllegalArgumentException} is what {@link ApiExceptionHandler}
 * renders as a 400 {@code VALIDATION_ECHOUEE}, so these read like any other bad
 * request.
 */
public final class PlageDates {

    /**
     * Widest range accepted. A year of rows is still a small aggregate here, but
     * an unbounded range is an unbounded scan, and these are endpoints a caller
     * can hit repeatedly.
     */
    public static final int JOURS_MAX = 366;

    private PlageDates() {
    }

    /**
     * The same check for an endpoint where the range itself is optional: both
     * bounds or neither, never one.
     *
     * <p>{@code /journal-connexions} used to guard with {@code if (du != null &&
     * au != null)}, which reads as caution and is the opposite: naming one bound
     * skipped every check, so {@code ?du=1900-01-01} answered 200 and scanned the
     * table from the beginning of time. Half a range is not a narrower query than
     * no range — it is a wider one, because the missing side is unbounded.
     *
     * <p>Kept here rather than repeated at each call site for the reason the
     * class exists: phase 6 already produced two endpoints that disagreed about
     * what a legal range was.
     */
    public static void verifierOptionnelle(LocalDate du, LocalDate au) {
        if (du == null && au == null) {
            return;
        }
        if (du == null || au == null) {
            throw new IllegalArgumentException(
                    "Les bornes du et au vont par paire : indiquez les deux ou aucune.");
        }
        verifier(du, au);
    }

    public static void verifier(LocalDate du, LocalDate au) {
        if (du == null || au == null) {
            throw new IllegalArgumentException("Les bornes du et au sont obligatoires.");
        }
        if (au.isBefore(du)) {
            throw new IllegalArgumentException("La borne au ne peut pas précéder du.");
        }
        if (du.plusDays(JOURS_MAX).isBefore(au)) {
            throw new IllegalArgumentException("Plage trop large : " + JOURS_MAX + " jours au maximum.");
        }
    }
}
