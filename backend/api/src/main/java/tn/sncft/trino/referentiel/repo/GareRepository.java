package tn.sncft.trino.referentiel.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import tn.sncft.trino.referentiel.domaine.Gare;

public interface GareRepository extends JpaRepository<Gare, Long>, JpaSpecificationExecutor<Gare> {
}
