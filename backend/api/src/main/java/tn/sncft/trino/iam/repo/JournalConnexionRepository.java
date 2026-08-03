package tn.sncft.trino.iam.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import tn.sncft.trino.iam.domaine.JournalConnexion;

public interface JournalConnexionRepository extends JpaRepository<JournalConnexion, Long> {
}
