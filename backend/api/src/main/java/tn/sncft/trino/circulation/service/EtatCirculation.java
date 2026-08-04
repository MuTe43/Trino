package tn.sncft.trino.circulation.service;

import java.util.List;

/**
 * The live state of one course: its latest fix plus a short window of recent
 * ones, which is what lets {@link CalculateurEta} measure a speed in chainage
 * units instead of trusting the ground speed on the ping.
 *
 * <p>{@code historique} is ordered oldest first and {@code dernier} is always
 * its last element.
 */
public record EtatCirculation(long courseId, FixPosition dernier, List<FixPosition> historique) {

    public EtatCirculation {
        historique = List.copyOf(historique);
    }
}
