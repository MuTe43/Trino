package tn.sncft.trino.notification.canal;

import tn.sncft.trino.notification.domaine.CanalType;
import tn.sncft.trino.notification.domaine.Notification;

/**
 * One way of delivering a notification.
 *
 * <p>The adapter seam of decision 7. Two implementations genuinely deliver
 * ({@link CanalInApp}, {@link CanalEmail}), one is an honest stub
 * ({@link CanalSmsStub}) and one is a recording no-op ({@link CanalAffichage}),
 * and which is which is stated here rather than discovered at a soutenance.
 *
 * <p>Implementations throw on failure and return normally on success. They never
 * write to the database and never catch their own errors into a status: the
 * dispatcher owns the {@code ENVOYE}/{@code ECHEC} transition, so one adapter
 * cannot invent a different meaning for "sent" than the others.
 */
public interface CanalNotification {

    CanalType type();

    /**
     * Delivers, or throws.
     *
     * @throws Exception whatever the transport failed with; the dispatcher
     *                   records it in {@code notification.erreur}
     */
    void envoyer(Notification notification) throws Exception;
}
