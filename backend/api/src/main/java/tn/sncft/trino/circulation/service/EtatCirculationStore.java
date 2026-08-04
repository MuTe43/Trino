package tn.sncft.trino.circulation.service;

import java.util.Optional;

/**
 * Hot state: where every running course currently is. Deliberately an interface
 * over an in-memory map (decision 4). The deliverable is a single instance, so
 * there is nothing to fan out between and no Redis to justify; if a second
 * instance ever appears, this is the one class that changes.
 *
 * <p>Never backed by {@code position_course} -- that table is append-only
 * history for reports and must not be read on the hot path.
 */
public interface EtatCirculationStore {

    /**
     * Records a fix and returns the resulting state. Fixes may arrive out of
     * order within a batch; the returned window is ordered by timestamp.
     */
    EtatCirculation mettreAJour(long courseId, FixPosition fix);

    Optional<EtatCirculation> lire(long courseId);

    /** Drops a course from hot state once its run is over. */
    void oublier(long courseId);
}
