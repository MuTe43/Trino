package tn.sncft.trino.notification.evenement;

import tn.sncft.trino.notification.domaine.Evenement;

import java.time.OffsetDateTime;

/**
 * The {@code notification} SSE delta: one notification, pushed on the
 * subscriber's own {@code abonne:} channel.
 *
 * <p>A delta like every other frame on this hub (invariant 5), never the list. A
 * bell mounting fetches what it missed from {@code GET /notifications} and then
 * applies these.
 *
 * <p>It deliberately carries no recipient and no channel identity: it arrives on
 * a connection that is already the recipient's, and repeating who they are would
 * only put an address on the wire for no reader.
 */
public record EvenementNotification(
        Long notificationId,
        Evenement evenement,
        String sujet,
        String contenu,
        Long courseId,
        OffsetDateTime emisAt) {
}
