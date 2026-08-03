package tn.sncft.trino.iam.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import tn.sncft.trino.iam.domaine.Utilisateur;

import java.util.Optional;

public interface UtilisateurRepository extends JpaRepository<Utilisateur, Long> {

    Optional<Utilisateur> findByEmail(String email);
}
