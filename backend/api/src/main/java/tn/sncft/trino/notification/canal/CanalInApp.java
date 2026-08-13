package tn.sncft.trino.notification.canal;

import org.springframework.stereotype.Component;
import tn.sncft.trino.diffusion.HubSse;
import tn.sncft.trino.notification.domaine.Abonnement;
import tn.sncft.trino.notification.domaine.CanalType;
import tn.sncft.trino.notification.domaine.Notification;
import tn.sncft.trino.notification.evenement.EvenementNotification;

import java.util.List;

/**
 * In-app delivery: a frame on the subscriber's own {@code abonne:} channel of
 * the existing SSE hub.
 *
 * <p>No second transport (the phase 6 rule, unchanged here): the bell in the
 * public header receives on the same connection the map and the station board
 * already share, which is what keeps a page that shows both from spending two of
 * the browser's six per-origin connections.
 *
 * <p>Published directly rather than through {@code PublicationApresCommit}: by
 * the time a channel runs, the notification row has already been committed by
 * the dispatcher, so there is no open transaction left to wait for.
 */
@Component
public class CanalInApp implements CanalNotification {

    private final HubSse hubSse;

    public CanalInApp(HubSse hubSse) {
        this.hubSse = hubSse;
    }

    @Override
    public CanalType type() {
        return CanalType.IN_APP;
    }

    @Override
    public void envoyer(Notification notification) {
        Abonnement abonnement = notification.getAbonnement();
        if (abonnement == null) {
            // Nothing to address it to. A notification with no subscription is
            // only reachable through a hand-written row.
            throw new IllegalStateException("Notification sans abonnement : aucun canal in-app à viser.");
        }
        String canal = abonnement.getJetonAnonyme() != null
                ? HubSse.canalAbonneJeton(abonnement.getJetonAnonyme())
                : HubSse.canalAbonneCompte(abonnement.getUtilisateurId());

        hubSse.publier(List.of(canal), "notification", new EvenementNotification(
                notification.getId(),
                notification.getEvenement(),
                notification.getSujet(),
                notification.getContenu(),
                notification.getCourseId(),
                notification.getEnvoyeAt()));
    }
}
