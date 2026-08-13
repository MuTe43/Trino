package tn.sncft.trino.commun;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Builds a valid, bounded {@link Pageable} from raw request params. Centralised
 * here so the max page size can't drift between GareService/LigneService/TrainService,
 * and so controllers never construct a Pageable themselves (that's service logic).
 */
public final class PageableUtils {

    private static final int TAILLE_MAX = 200;

    private PageableUtils() {
    }

    public static Pageable de(int page, int taille) {
        return de(page, taille, Sort.unsorted());
    }

    /**
     * Same clamping, with an ordering. Separate overload rather than a default
     * sort: an unordered page is only deterministic while the database happens
     * to return rows in insertion order, and a caller that needs "newest first"
     * has to say so.
     */
    public static Pageable de(int page, int taille, Sort tri) {
        int pageValide = Math.max(page, 0);
        int tailleValide = Math.min(Math.max(taille, 1), TAILLE_MAX);
        return PageRequest.of(pageValide, tailleValide, tri);
    }
}
