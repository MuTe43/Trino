package tn.sncft.trino.iam.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import tn.sncft.trino.iam.domaine.RefreshToken;

import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String hash);
}
