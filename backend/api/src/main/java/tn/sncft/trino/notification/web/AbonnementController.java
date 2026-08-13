package tn.sncft.trino.notification.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tn.sncft.trino.commun.RessourceIntrouvableException;
import tn.sncft.trino.notification.dto.AbonnementCreateDTO;
import tn.sncft.trino.notification.dto.AbonnementDTO;
import tn.sncft.trino.notification.dto.ResultatAbonnement;
import tn.sncft.trino.notification.service.ServiceAbonnement;
import tn.sncft.trino.securite.IdentiteAbonne;
import tn.sncft.trino.securite.JetonAbonne;
import tn.sncft.trino.securite.ResolveurIdentiteAbonne;

import java.util.List;

/**
 * Following and unfollowing, for anyone -- no account required.
 *
 * <p>That is the point of the endpoint rather than an oversight: a passenger
 * checking whether their train is late has no login, and putting one in front of
 * "Suivre ce train" would mean the notification use case is delivered to nobody
 * who actually wants it.
 *
 * <p>No logic here (invariant 7). The controller resolves who is calling, hands
 * that to {@link ServiceAbonnement}, and translates one boolean into 201 or 200.
 */
@RestController
@RequestMapping("/api/v1/abonnements")
public class AbonnementController {

    private final ServiceAbonnement serviceAbonnement;
    private final ResolveurIdentiteAbonne resolveur;

    public AbonnementController(ServiceAbonnement serviceAbonnement, ResolveurIdentiteAbonne resolveur) {
        this.serviceAbonnement = serviceAbonnement;
        this.resolveur = resolveur;
    }

    /**
     * Creates the subscription, minting an anonymous identity for a first-time
     * visitor.
     *
     * <p>The minted token goes back as an {@code HttpOnly} cookie and is
     * <em>not</em> in the response body. A caller that cannot hold a cookie
     * supplies its own token in {@code X-Abonne} instead -- which is legitimate,
     * since the value is only ever a key to that same caller's own rows.
     *
     * <p>201 on a new subscription, 200 when this identity already followed this
     * target: pressing "Suivre" twice is not an error, and answering 409 would
     * make a double-clicked button look broken.
     */
    @PostMapping
    public ResponseEntity<AbonnementDTO> creer(@Valid @RequestBody AbonnementCreateDTO requete,
                                               HttpServletRequest httpRequete) {
        IdentiteAbonne identite = resolveur.resoudre(httpRequete).orElse(null);
        ResponseCookie cookie = null;
        if (identite == null) {
            String jeton = JetonAbonne.generer();
            identite = IdentiteAbonne.deJeton(jeton);
            cookie = resolveur.cookiePour(jeton);
        }

        ResultatAbonnement resultat = serviceAbonnement.enregistrer(identite, requete);

        ResponseEntity.BodyBuilder reponse = ResponseEntity
                .status(resultat.cree() ? HttpStatus.CREATED : HttpStatus.OK);
        if (cookie != null) {
            reponse.header(HttpHeaders.SET_COOKIE, cookie.toString());
        }
        return reponse.body(resultat.abonnement());
    }

    /**
     * Scoped by the caller's identity. An id belonging to someone else is
     * {@code 404}, not {@code 403} -- see {@link ServiceAbonnement#supprimer}.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> supprimer(@PathVariable Long id, HttpServletRequest httpRequete) {
        serviceAbonnement.supprimer(identiteRequise(httpRequete), id);
        return ResponseEntity.noContent().build();
    }

    /**
     * This caller's subscriptions.
     *
     * <p>An empty list for a visitor with no identity yet, rather than an error:
     * "you have not subscribed to anything" and "you have no cookie" are the same
     * state as far as the bell is concerned, and the page renders identically.
     */
    @GetMapping("/miennes")
    public List<AbonnementDTO> miennes(HttpServletRequest httpRequete) {
        return resolveur.resoudre(httpRequete)
                .map(serviceAbonnement::miennes)
                .orElseGet(List::of);
    }

    /**
     * For the paths where doing nothing is not an option: deleting something
     * requires knowing whose it is.
     */
    private IdentiteAbonne identiteRequise(HttpServletRequest httpRequete) {
        return resolveur.resoudre(httpRequete)
                .orElseThrow(() -> new RessourceIntrouvableException("Aucun abonnement pour cette identité."));
    }
}
