package tn.sncft.trino.notification.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.sncft.trino.notification.canal.CanalNotification;
import tn.sncft.trino.notification.domaine.CanalType;
import tn.sncft.trino.notification.domaine.Notification;
import tn.sncft.trino.notification.domaine.StatutNotification;
import tn.sncft.trino.notification.repo.NotificationRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Hands one already-persisted notification to its channel and records what
 * happened.
 *
 * <p>Asynchronous, on {@link ConfigurationNotification#EXECUTEUR}, and one task
 * per notification rather than one per event: a subscriber whose EMAIL is stuck
 * against a dead SMTP server must not hold up the IN_APP frame that would have
 * reached the bell instantly.
 *
 * <p>The status transition lives here and only here, which is why
 * {@link CanalNotification} implementations are allowed to just throw. Each
 * adapter deciding for itself what counts as sent is how "delivered" ends up
 * meaning four different things.
 */
@Service
public class Dispatcheur {

    private static final Logger log = LoggerFactory.getLogger(Dispatcheur.class);

    /**
     * A stored {@code erreur} is read by an administrator, not parsed. Postgres
     * would take the whole stack trace, but a column nobody can skim is a column
     * nobody reads.
     */
    private static final int LONGUEUR_MAX_ERREUR = 500;

    private final NotificationRepository notificationRepository;
    private final Map<CanalType, CanalNotification> canaux = new EnumMap<>(CanalType.class);

    public Dispatcheur(NotificationRepository notificationRepository, List<CanalNotification> adaptateurs) {
        this.notificationRepository = notificationRepository;
        for (CanalNotification adaptateur : adaptateurs) {
            this.canaux.put(adaptateur.type(), adaptateur);
        }
    }

    /**
     * Delivers the notification with this id, whatever the outcome.
     *
     * <p>Takes an id rather than the entity: it crosses a thread boundary, and
     * a detached entity built in another transaction is exactly the thing that
     * later throws {@code LazyInitializationException} at the one moment nobody
     * is watching -- inside an async task whose exception nothing surfaces.
     */
    @Async(ConfigurationNotification.EXECUTEUR)
    @Transactional
    public void remettre(Long notificationId) {
        Notification notification = notificationRepository.findById(notificationId).orElse(null);
        if (notification == null) {
            return;
        }
        // Stamped before the attempt, so a channel that reads it (CanalInApp
        // puts it in the frame) sees when this was emitted rather than null.
        notification.setEnvoyeAt(OffsetDateTime.now(ZoneOffset.UTC));

        CanalNotification canal = canaux.get(notification.getCanal());
        if (canal == null) {
            // Unreachable while every CanalType has an adapter. It stops a
            // channel added later from silently leaving rows at EN_ATTENTE.
            echouer(notification, "Aucun adaptateur pour le canal " + notification.getCanal() + ".");
            return;
        }

        try {
            canal.envoyer(notification);
            notification.setStatut(StatutNotification.ENVOYE);
            notification.setErreur(null);
        } catch (Exception e) {
            // Broad on purpose: this is the top of an async task. An exception
            // escaping here goes to the executor's default handler and the row
            // stays EN_ATTENTE for ever -- a notification that neither arrived
            // nor recorded a failure.
            log.warn("Échec d'envoi de la notification {} sur {} : {}",
                    notification.getId(), notification.getCanal(), e.toString());
            echouer(notification, e.toString());
            return;
        }
        notificationRepository.save(notification);
    }

    private void echouer(Notification notification, String message) {
        notification.setStatut(StatutNotification.ECHEC);
        notification.setErreur(message.length() > LONGUEUR_MAX_ERREUR
                ? message.substring(0, LONGUEUR_MAX_ERREUR)
                : message);
        notificationRepository.save(notification);
    }
}
