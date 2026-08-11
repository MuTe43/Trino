package tn.sncft.trino.referentiel.repo;

import org.springframework.data.jpa.domain.Specification;
import tn.sncft.trino.referentiel.domaine.Gare;

/**
 * Optional filters for {@link GareRepository}, built with the Criteria API
 * rather than a {@code @Query} with {@code :param is null} -- see
 * {@code tn.sncft.trino.iam.repo.JournalConnexionSpecifications} for why.
 */
public final class GareSpecifications {

    private GareSpecifications() {
    }

    public static Specification<Gare> regionEgale(String region) {
        return (root, query, cb) -> region == null ? null : cb.equal(cb.lower(root.get("region")), region.toLowerCase());
    }

    public static Specification<Gare> nomOuCodeContient(String q) {
        return (root, query, cb) -> {
            if (q == null) {
                return null;
            }
            String motif = "%" + q.toLowerCase() + "%";
            return cb.or(cb.like(cb.lower(root.get("nom")), motif), cb.like(cb.lower(root.get("code")), motif));
        };
    }
}
