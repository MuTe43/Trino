package tn.sncft.trino.notification.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tn.sncft.trino.commun.dto.PageDTO;
import tn.sncft.trino.notification.dto.NotificationDTO;
import tn.sncft.trino.notification.service.ServiceAbonnement;
import tn.sncft.trino.securite.ResolveurIdentiteAbonne;

import java.util.List;

/**
 * What the bell reads on mount, and what it pages through when the panel is
 * opened. Live arrivals come over SSE on the caller's own {@code abonne:}
 * channel instead.
 *
 * <p>Scoped by the caller's identity and by nothing else -- there is no
 * parameter naming whose notifications these are, which is the same rule the
 * {@code abonne:} SSE channel follows and for the same reason.
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final ServiceAbonnement serviceAbonnement;
    private final ResolveurIdentiteAbonne resolveur;

    public NotificationController(ServiceAbonnement serviceAbonnement, ResolveurIdentiteAbonne resolveur) {
        this.serviceAbonnement = serviceAbonnement;
        this.resolveur = resolveur;
    }

    /**
     * An empty page for a visitor with no identity, rather than a 401. This is a
     * public endpoint on a public portal: a first-time visitor has nothing, and
     * that is a normal state, not a failure to authenticate.
     */
    @GetMapping
    public PageDTO<NotificationDTO> lister(@RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int taille,
                                           HttpServletRequest httpRequete) {
        return resolveur.resoudre(httpRequete)
                .map(identite -> versPageDTO(serviceAbonnement.notifications(identite, page, taille)))
                .orElseGet(() -> new PageDTO<>(List.of(), page, taille, 0));
    }

    private static PageDTO<NotificationDTO> versPageDTO(Page<NotificationDTO> notifications) {
        return new PageDTO<>(
                notifications.getContent(),
                notifications.getNumber(),
                notifications.getSize(),
                notifications.getTotalElements());
    }
}
