package tn.sncft.trino.referentiel.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import tn.sncft.trino.referentiel.domaine.Desserte;

import java.util.List;

public interface DesserteRepository extends JpaRepository<Desserte, Long> {

    @Query("select d from Desserte d join fetch d.gare where d.ligne.id = :ligneId order by d.ordre asc")
    List<Desserte> findByLigneIdOrderByOrdreAsc(Long ligneId);
}
