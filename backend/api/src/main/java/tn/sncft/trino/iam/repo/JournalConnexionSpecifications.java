package tn.sncft.trino.iam.repo;

import org.springframework.data.jpa.domain.Specification;
import tn.sncft.trino.iam.domaine.JournalConnexion;

import java.time.OffsetDateTime;

/**
 * Optional filters for {@link JournalConnexionRepository}, built with the
 * Criteria API rather than a {@code @Query} with {@code :param is null}.
 * That pattern threw {@code could not determine data type of parameter $7}
 * in phase 6 -- PostgreSQL cannot type an untyped bind parameter, and the
 * Criteria API never produces one: an absent filter is simply not added to
 * the predicate list.
 */
public final class JournalConnexionSpecifications {

    private JournalConnexionSpecifications() {
    }

    public static Specification<JournalConnexion> succesEgal(Boolean succes) {
        return (root, query, cb) -> succes == null ? null : cb.equal(root.get("succes"), succes);
    }

    public static Specification<JournalConnexion> utilisateurIdEgal(Long utilisateurId) {
        return (root, query, cb) -> utilisateurId == null ? null : cb.equal(root.get("utilisateurId"), utilisateurId);
    }

    public static Specification<JournalConnexion> horodatageDepuis(OffsetDateTime debut) {
        return (root, query, cb) -> debut == null ? null : cb.greaterThanOrEqualTo(root.get("horodatage"), debut);
    }

    public static Specification<JournalConnexion> horodatageAvant(OffsetDateTime finExclusive) {
        return (root, query, cb) -> finExclusive == null ? null : cb.lessThan(root.get("horodatage"), finExclusive);
    }
}
