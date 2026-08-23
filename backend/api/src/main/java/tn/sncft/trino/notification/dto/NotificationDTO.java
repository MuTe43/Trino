package tn.sncft.trino.notification.dto;

import tn.sncft.trino.notification.domaine.CanalType;
import tn.sncft.trino.notification.domaine.Evenement;
import tn.sncft.trino.notification.domaine.StatutNotification;

import java.time.OffsetDateTime;

/**
 * One emitted notification, as the bell shows it.
 *
 * <p>{@code destinataire} and {@code erreur} are deliberately not exposed. The
 * first is either the reader's own address (which they know) or an internal
 * subscription reference (which means nothing to them); the second is an SMTP
 * diagnostic for whoever runs the system, not for a passenger.
 *
 * <p>{@code envoyeAt} is stamped at the top of the dispatch, not at the end, so
 * it is null only while the row is still queued -- once an adapter has been
 * reached it carries a time whether that adapter succeeded or threw. It can
 * still be null, so the client orders on {@code id}: monotonic, never null, and
 * on an append-only table it is the emission order.
 */
public record NotificationDTO(
        Long id,
        Evenement evenement,
        Long courseId,
        CanalType canal,
        String sujet,
        String contenu,
        StatutNotification statut,
        OffsetDateTime envoyeAt) {
}
