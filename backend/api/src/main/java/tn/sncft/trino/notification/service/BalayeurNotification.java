package tn.sncft.trino.notification.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.sncft.trino.notification.repo.NotificationRepository;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Closes notifications that were never dispatched.
 *
 * <p>{@link Dispatcheur} covers a channel that throws: the row becomes
 * {@code ECHEC} and carries the reason. It cannot cover a process that dies,
 * because dispatch state lives entirely in an in-memory executor -- there is no
 * queue, no outbox and nothing that resumes. Measured in phase 8: a
 * {@code taskkill /F} of the API left <b>344</b> rows at {@code EN_ATTENTE}, and
 * nothing would ever have moved them.
 *
 * <p>{@code EN_ATTENTE} therefore has to mean "in flight right now". Anything
 * older than the process that would be flying it is not in flight, it is lost,
 * and saying so is the whole point: an administrator reading the table can
 * otherwise not tell a notification being delivered this second from one
 * abandoned last week.
 *
 * <p>Nothing here retries. A retry needs an idempotent channel and a backoff,
 * and a delay notification is worth very little by the time a retry would land
 * -- the train has arrived. Recording the loss is the honest half, and it is the
 * half that can be built without a broker (decision 4).
 */
@Service
public class BalayeurNotification {

    private static final Logger log = LoggerFactory.getLogger(BalayeurNotification.class);

    static final String CAUSE_DEMARRAGE =
            "Processus interrompu avant la remise : notification orpheline détectée au démarrage.";

    static final String CAUSE_EXPIRATION =
            "Restée en attente au-delà du délai de remise : remise abandonnée.";

    /**
     * How long a row may legitimately sit at {@code EN_ATTENTE}. Dispatch is one
     * async task on a bounded pool, and the SMTP adapter's own timeouts total 15
     * seconds, so ten minutes is two orders of magnitude past anything healthy
     * while leaving a saturated pool room to drain rather than being declared
     * dead under load.
     */
    static final Duration DELAI_REMISE = Duration.ofMinutes(10);

    private final NotificationRepository notificationRepository;

    /**
     * When this process started. Every {@code EN_ATTENTE} row older than this
     * belongs to a process that no longer exists, which is an exact test and not
     * an age heuristic -- it cannot race a notification created a moment ago by
     * this process, which the web server is already accepting requests for by the
     * time {@link ApplicationReadyEvent} fires.
     */
    private final OffsetDateTime demarrage = OffsetDateTime.now(ZoneOffset.UTC);

    public BalayeurNotification(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void balayerAuDemarrage() {
        int orphelines = notificationRepository.marquerEnAttenteEnEchec(CAUSE_DEMARRAGE, demarrage);
        if (orphelines > 0) {
            log.warn("{} notification(s) orpheline(s) d'un processus précédent marquée(s) en échec.", orphelines);
        }
    }

    /**
     * Every five minutes, for the case the startup sweep cannot see: this
     * process is up, took the row, and lost it -- a task rejected by a saturated
     * executor, or an error escaping above the {@code catch} in
     * {@link Dispatcheur}.
     */
    @Scheduled(fixedDelay = 300_000)
    @Transactional
    public void balayerPerimees() {
        OffsetDateTime limite = OffsetDateTime.now(ZoneOffset.UTC).minus(DELAI_REMISE);
        int perimees = notificationRepository.marquerEnAttenteEnEchec(CAUSE_EXPIRATION, limite);
        if (perimees > 0) {
            log.warn("{} notification(s) restée(s) en attente au-delà de {} marquée(s) en échec.",
                    perimees, DELAI_REMISE);
        }
    }
}
