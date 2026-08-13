package tn.sncft.trino.notification.canal;

import org.springframework.stereotype.Component;
import tn.sncft.trino.notification.domaine.CanalType;
import tn.sncft.trino.notification.domaine.Notification;

/**
 * Station-board delivery, which is already done by the time this runs.
 *
 * <p>The board consumes {@code gare:{id}} and has since phase 4; the delay that
 * triggered this notification reached it on that channel before the engine ever
 * looked at a subscription. Emitting anything here would put the same change on
 * the board twice.
 *
 * <p>So why does the adapter exist at all? Because {@code AFFICHAGE} is a member
 * of {@code CanalType}, and a channel with no adapter leaves its notification
 * rows stuck at {@code EN_ATTENTE} for ever -- a queue that is never drained and
 * never reported. Recording the row as sent is the accurate statement: it was
 * delivered, on a channel that already existed. The alternative, a special case
 * inside the dispatcher, would put the same knowledge somewhere less findable.
 */
@Component
public class CanalAffichage implements CanalNotification {

    @Override
    public CanalType type() {
        return CanalType.AFFICHAGE;
    }

    @Override
    public void envoyer(Notification notification) {
        // Intentionally empty -- see the class comment.
    }
}
