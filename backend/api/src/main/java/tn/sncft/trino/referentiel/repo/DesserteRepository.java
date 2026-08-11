package tn.sncft.trino.referentiel.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import tn.sncft.trino.referentiel.domaine.Desserte;

import java.util.List;

public interface DesserteRepository extends JpaRepository<Desserte, Long> {

    @Query("select d from Desserte d join fetch d.gare where d.ligne.id = :ligneId order by d.ordre asc")
    List<Desserte> findByLigneIdOrderByOrdreAsc(Long ligneId);

    /**
     * Which lignes serve a gare. Used to fan an incident declared at a station
     * out to the lignes passing through it, so a client watching lignes does not
     * have to subscribe to every gare channel on the network to be sure of
     * seeing station incidents.
     */
    @Query("select distinct d.ligne.id from Desserte d where d.gare.id = :gareId")
    List<Long> findLigneIdsParGare(Long gareId);
}
