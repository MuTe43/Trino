package tn.sncft.trino.referentiel.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import tn.sncft.trino.referentiel.domaine.Ligne;

public interface LigneRepository extends JpaRepository<Ligne, Long> {
}
