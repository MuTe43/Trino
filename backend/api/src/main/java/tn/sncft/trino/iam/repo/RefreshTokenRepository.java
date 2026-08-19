package tn.sncft.trino.iam.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tn.sncft.trino.iam.domaine.RefreshToken;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String hash);

    /**
     * Deletes rows that can never authenticate anything again: expired, or
     * revoked and past the point where keeping the record proves anything.
     *
     * <p>A revoked token is deleted on the same schedule as an expired one
     * rather than immediately. Between revocation and expiry the row is the only
     * evidence that a presented token <em>was</em> issued and then withdrawn, and
     * deleting it turns "this refresh token was revoked" into "this refresh token
     * never existed" -- the same answer as a forged one, on the audit trail
     * somebody would be reading precisely because they suspect the difference.
     *
     * <p>Bulk delete rather than findAll-then-delete: this runs on a schedule
     * against a table nobody is watching, and loading every expired row into the
     * persistence context to delete it one at a time is how a nightly job
     * becomes an outage.
     */
    @Modifying
    @Query("delete from RefreshToken r where r.expireAt < :limite")
    int supprimerExpires(@Param("limite") OffsetDateTime limite);
}
