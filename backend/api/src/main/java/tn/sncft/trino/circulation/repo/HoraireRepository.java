package tn.sncft.trino.circulation.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import tn.sncft.trino.circulation.domaine.Horaire;

import java.util.List;

public interface HoraireRepository extends JpaRepository<Horaire, Long> {

    @Query("""
            select h from Horaire h
              join fetch h.ligne
              join fetch h.train
            where h.actif = true and h.ligne.actif = true and h.train.actif = true
            order by h.heureDepart asc
            """)
    List<Horaire> findActifs();
}
