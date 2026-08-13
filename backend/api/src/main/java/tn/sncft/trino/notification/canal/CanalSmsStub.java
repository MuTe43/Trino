package tn.sncft.trino.notification.canal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tn.sncft.trino.notification.domaine.CanalType;
import tn.sncft.trino.notification.domaine.Notification;

/**
 * SMS delivery, as a stub. It logs the payload and reports success; no message
 * leaves the machine.
 *
 * <p>Named {@code Stub} rather than {@code CanalSms} on purpose. The class name
 * is the honest part of decision 7: a reader scanning the package sees at once
 * which channels work, and nobody deploys this believing SMS is wired up. There
 * is no Twilio account, no credentials, and no request is made.
 *
 * <p>The shape below is what the real integration would replace -- a POST to
 * {@code /2010-04-01/Accounts/{sid}/Messages.json} with {@code To}, {@code From}
 * and {@code Body}, authenticated with the account SID and auth token. That is
 * the whole change: this class, plus two configuration properties.
 *
 * <p>It reports success rather than failure so the {@code ECHEC} status keeps
 * meaning "a working channel could not deliver". A stub filling the table with
 * failures would drown the one row that matters when SMTP really is down.
 */
@Component
public class CanalSmsStub implements CanalNotification {

    private static final Logger log = LoggerFactory.getLogger(CanalSmsStub.class);

    @Override
    public CanalType type() {
        return CanalType.SMS;
    }

    @Override
    public void envoyer(Notification notification) {
        // INTEGRATION POINT -- Twilio. Replace this log with the REST call.
        log.info("[SMS STUB] To={} Body={}", notification.getDestinataire(), notification.getContenu());
    }
}
