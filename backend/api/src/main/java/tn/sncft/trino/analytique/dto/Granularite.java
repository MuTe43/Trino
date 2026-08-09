package tn.sncft.trino.analytique.dto;

/**
 * Bucket width for the punctuality report.
 *
 * <p>The {@code date_trunc} unit is carried here rather than taken from the
 * query string: the value reaches SQL as an identifier, not as a bind
 * parameter, so it must never be caller-supplied text.
 */
public enum Granularite {

    JOUR("day"),
    MOIS("month");

    private final String uniteSql;

    Granularite(String uniteSql) {
        this.uniteSql = uniteSql;
    }

    public String uniteSql() {
        return uniteSql;
    }
}
