package tn.sncft.trino.notification.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import tn.sncft.trino.notification.domaine.Abonnement;
import tn.sncft.trino.notification.domaine.CibleType;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AbonnementRepository extends JpaRepository<Abonnement, Long> {

    /** Subscribers of one target. The engine's lookup for COURSE and LIGNE. */
    List<Abonnement> findByCibleTypeAndCibleId(CibleType cibleType, Long cibleId);

    /**
     * Subscribers of several targets of one type. Used for the gares a course
     * has not yet cleared; callers must not pass an empty collection, which
     * Hibernate does not bind reliably to an {@code in} clause.
     */
    List<Abonnement> findByCibleTypeAndCibleIdIn(CibleType cibleType, Collection<Long> cibleIds);

    List<Abonnement> findByJetonAnonymeOrderByIdDesc(String jetonAnonyme);

    List<Abonnement> findByUtilisateurIdOrderByIdDesc(Long utilisateurId);

    /**
     * Scoped lookups, and the only way this module ever loads a subscription by
     * id. Taking the identity as part of the query rather than loading the row
     * and comparing afterwards is what makes "scoped by token" hard to get wrong
     * later: there is no overload that returns someone else's subscription.
     */
    Optional<Abonnement> findByIdAndJetonAnonyme(Long id, String jetonAnonyme);

    Optional<Abonnement> findByIdAndUtilisateurId(Long id, Long utilisateurId);

    Optional<Abonnement> findByJetonAnonymeAndCibleTypeAndCibleId(
            String jetonAnonyme, CibleType cibleType, Long cibleId);

    Optional<Abonnement> findByUtilisateurIdAndCibleTypeAndCibleId(
            Long utilisateurId, CibleType cibleType, Long cibleId);
}
