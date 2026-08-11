package tn.sncft.trino.referentiel.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import tn.sncft.trino.referentiel.domaine.Train;

public interface TrainRepository extends JpaRepository<Train, Long>, JpaSpecificationExecutor<Train> {
}
