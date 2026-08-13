package tn.sncft.trino.notification.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import tn.sncft.trino.notification.domaine.Evenement;
import tn.sncft.trino.notification.domaine.RegleAlerte;

import java.util.List;

public interface RegleAlerteRepository extends JpaRepository<RegleAlerte, Long> {

    /**
     * The engine's only read. Several rules may match one event -- an
     * administrator can want a 5-minute IN_APP alert and a 30-minute EMAIL one
     * at once -- so this returns a list, not an optional.
     */
    List<RegleAlerte> findByEvenementAndActifTrue(Evenement evenement);

    /**
     * Unordered on purpose. Sorting on {@code evenement} in SQL orders the
     * stored varchar, which is alphabetical -- {@code COURSE_ANNULEE} before
     * {@code RETARD_SEUIL} -- and has nothing to do with the enum's declaration
     * order that the console's own labels follow. The service sorts by ordinal.
     */
    List<RegleAlerte> findAllByOrderByIdAsc();
}
