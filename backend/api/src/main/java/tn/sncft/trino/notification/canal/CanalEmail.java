package tn.sncft.trino.notification.canal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import tn.sncft.trino.notification.domaine.CanalType;
import tn.sncft.trino.notification.domaine.Notification;

/**
 * Email delivery over real SMTP.
 *
 * <p>The point of this channel is that it is not a mock. Mailpit runs in
 * docker-compose, listens on 1025 and shows what arrived at
 * {@code localhost:8025}: a real message, in a real inbox, with no credentials
 * and no cost. "We designed an adapter" and "here is the email, on screen" are
 * different sentences at a soutenance.
 *
 * <p>Failures are allowed to propagate. An unreachable SMTP server has to leave
 * an {@code ECHEC} row with its cause -- swallowing it here would turn a
 * misconfigured mail host into notifications that silently never arrive. The
 * timeouts in {@code application.yml} are what keep that failure fast: without
 * them a black-holed TCP connection holds a dispatcher thread until the OS
 * gives up.
 */
@Component
public class CanalEmail implements CanalNotification {

    private final JavaMailSender expediteur;
    private final String adresseExpediteur;

    public CanalEmail(JavaMailSender expediteur,
                      @Value("${trino.notification.email-expediteur}") String adresseExpediteur) {
        this.expediteur = expediteur;
        this.adresseExpediteur = adresseExpediteur;
    }

    @Override
    public CanalType type() {
        return CanalType.EMAIL;
    }

    @Override
    public void envoyer(Notification notification) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(adresseExpediteur);
        message.setTo(notification.getDestinataire());
        message.setSubject(notification.getSujet());
        message.setText(notification.getContenu());
        expediteur.send(message);
    }
}
