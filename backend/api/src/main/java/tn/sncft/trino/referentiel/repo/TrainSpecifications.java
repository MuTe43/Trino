package tn.sncft.trino.referentiel.repo;

import org.springframework.data.jpa.domain.Specification;
import tn.sncft.trino.referentiel.domaine.Train;
import tn.sncft.trino.referentiel.domaine.TypeTrain;

/**
 * Optional filters for {@link TrainRepository}, built with the Criteria API
 * rather than a {@code @Query} with {@code :param is null} -- see
 * {@code tn.sncft.trino.iam.repo.JournalConnexionSpecifications} for why.
 */
public final class TrainSpecifications {

    private TrainSpecifications() {
    }

    public static Specification<Train> typeEgal(TypeTrain type) {
        return (root, query, cb) -> type == null ? null : cb.equal(root.get("type"), type);
    }

    public static Specification<Train> ligneIdEgal(Long ligneId) {
        return (root, query, cb) -> ligneId == null ? null : cb.equal(root.get("ligne").get("id"), ligneId);
    }
}
