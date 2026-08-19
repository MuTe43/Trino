package tn.sncft.trino.iam.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.sncft.trino.iam.repo.RefreshTokenRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Deletes refresh tokens that have expired.
 *
 * <p>{@code refresh_token} had no sweep at all: every login since phase 1 left a
 * row, and nothing ever removed one. The table only grows, and it grows fastest
 * exactly where it matters least -- a demo machine where the same four accounts
 * log in repeatedly.
 *
 * <p>Nothing here revokes anything or shortens a session. A row is only removed
 * once {@code expireAt} has already passed, which means the token it hashes was
 * refused by {@code JetonService} before this class ever looked at it. Deleting
 * it changes no answer the API gives.
 *
 * <p>Its own bean rather than a method on {@link JetonService}: that class is on
 * the login path and every one of its methods is reachable from a controller,
 * whereas this is a scheduled job with no caller. Mixing the two would make
 * "which of these can a request trigger" a question about annotations.
 */
@Service
public class PurgeJetons {

    private static final Logger log = LoggerFactory.getLogger(PurgeJetons.class);

    /**
     * Retained past expiry so the audit trail keeps a window in which a
     * presented-but-expired token can still be told apart from one that was
     * never issued. Seven days matches the refresh lifetime itself, so at any
     * moment the table holds at most two lifetimes of history.
     */
    static final int JOURS_RETENTION = 7;

    private final RefreshTokenRepository refreshTokenRepository;

    public PurgeJetons(RefreshTokenRepository refreshTokenRepository) {
        this.refreshTokenRepository = refreshTokenRepository;
    }

    /**
     * Daily at 03:30 Africa/Tunis -- after {@code GenerateurCourses} has
     * materialised the new service day at 03:00, so the two scheduled writes of
     * the night do not contend, and inside the window a backup taken at 02:00
     * has already captured.
     */
    @Scheduled(cron = "0 30 3 * * *", zone = "Africa/Tunis")
    @Transactional
    public void purger() {
        OffsetDateTime limite = OffsetDateTime.now(ZoneOffset.UTC).minusDays(JOURS_RETENTION);
        int supprimes = refreshTokenRepository.supprimerExpires(limite);
        if (supprimes > 0) {
            log.info("Purge des jetons de rafraîchissement : {} ligne(s) expirée(s) supprimée(s).", supprimes);
        }
    }
}
