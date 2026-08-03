package tn.sncft.trino.referentiel.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import tn.sncft.trino.referentiel.domaine.Gare;

public interface GareRepository extends JpaRepository<Gare, Long> {
}
