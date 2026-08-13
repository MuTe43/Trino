package tn.sncft.trino.notification.web;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tn.sncft.trino.notification.dto.RegleAlerteCreateDTO;
import tn.sncft.trino.notification.dto.RegleAlerteDTO;
import tn.sncft.trino.notification.dto.RegleAlerteUpdateDTO;
import tn.sncft.trino.notification.service.ServiceRegleAlerte;

import java.util.List;

/**
 * <em>Gérer les alertes</em>: the administrator's screen for deciding what is
 * worth notifying about.
 *
 * <p>{@code ADMINISTRATEUR} only, gated in two places (invariant 9) -- a URL
 * rule in {@code ConfigurationSecurite} and {@code @PreAuthorize} on
 * {@link ServiceRegleAlerte}. Both {@code POST} and {@code PATCH} carry
 * {@code @Valid} bodies, which is exactly the case the URL rule exists for: it
 * runs in the filter chain, ahead of argument resolution, so a forbidden caller
 * with a malformed payload gets 403 and not a 400 telling them about a field on
 * an endpoint they may not touch.
 *
 * <p>Unpaginated: there are four rules by default and an administrator adding a
 * fifth is a rare event. A page envelope here would be ceremony around a list
 * that fits on one screen.
 */
@RestController
@RequestMapping("/api/v1/regles-alerte")
public class RegleAlerteController {

    private final ServiceRegleAlerte serviceRegleAlerte;

    public RegleAlerteController(ServiceRegleAlerte serviceRegleAlerte) {
        this.serviceRegleAlerte = serviceRegleAlerte;
    }

    @GetMapping
    public List<RegleAlerteDTO> lister() {
        return serviceRegleAlerte.lister();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RegleAlerteDTO creer(@Valid @RequestBody RegleAlerteCreateDTO requete) {
        return serviceRegleAlerte.creer(requete);
    }

    @PatchMapping("/{id}")
    public RegleAlerteDTO mettreAJour(@PathVariable Long id,
                                      @Valid @RequestBody RegleAlerteUpdateDTO requete) {
        return serviceRegleAlerte.mettreAJour(id, requete);
    }
}
