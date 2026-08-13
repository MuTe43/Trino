package tn.sncft.trino.securite;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Works out who a public notification request belongs to, and mints the
 * anonymous token when there is none yet.
 *
 * <p>Resolution order is account first, then the {@code X-Abonne} header, then
 * the cookie. Account first because a subscription made while signed in is
 * stored against the account ({@code chk_abonnement_identite} allows exactly one
 * identity), so reading it back by cookie would show the subscriber an empty
 * list of their own subscriptions.
 *
 * <p>Nothing here reads a query parameter, and that is the security property the
 * phase file asks for: the caller does not get to <em>name</em> whose
 * subscriptions these are. A token in a query string would also end up in
 * access logs, in {@code Referer} headers and in browser history, which is not
 * where a bearer credential belongs.
 */
@Component
public class ResolveurIdentiteAbonne {

    /**
     * A year. The subscription list outlives the visit -- a passenger who
     * follows their commuter train expects it still followed next week -- and a
     * session-scoped cookie would silently orphan every row it created.
     */
    private static final Duration DUREE_COOKIE = Duration.ofDays(365);

    /**
     * The identity of this request, if it has one. Empty means an anonymous
     * caller who has never subscribed: readers answer with nothing, and
     * {@code POST /abonnements} mints a token.
     */
    public Optional<IdentiteAbonne> resoudre(HttpServletRequest requete) {
        Long utilisateurId = utilisateurAuthentifie();
        if (utilisateurId != null) {
            return Optional.of(IdentiteAbonne.deCompte(utilisateurId));
        }
        return jetonPresente(requete).map(IdentiteAbonne::deJeton);
    }

    /** The raw token carried by this request, header before cookie. Validated. */
    public Optional<String> jetonPresente(HttpServletRequest requete) {
        String entete = requete.getHeader(JetonAbonne.NOM_ENTETE);
        if (entete != null && JetonAbonne.valide(entete.trim())) {
            return Optional.of(entete.trim());
        }
        Cookie[] cookies = requete.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie cookie : cookies) {
            if (JetonAbonne.NOM_COOKIE.equals(cookie.getName()) && JetonAbonne.valide(cookie.getValue())) {
                return Optional.of(cookie.getValue());
            }
        }
        return Optional.empty();
    }

    private Long utilisateurAuthentifie() {
        Authentication authentification = SecurityContextHolder.getContext().getAuthentication();
        if (authentification == null || !authentification.isAuthenticated()) {
            return null;
        }
        if (authentification.getPrincipal() instanceof DetailsUtilisateur details) {
            return details.getUtilisateur().id();
        }
        return null;
    }

    /**
     * The cookie carrying a freshly minted token.
     *
     * <p>{@code HttpOnly}: the browser never needs to read the value -- every
     * call that needs it sends the cookie automatically -- so keeping it out of
     * JavaScript costs nothing and takes the token out of reach of anything
     * injected into the page.
     *
     * <p>{@code SameSite=Lax} and not {@code None}: the portal on port 3000 and
     * the API on 8080 are a different <em>origin</em> but the same
     * <em>site</em> (SameSite ignores the port), so Lax is sent on these
     * requests. {@code None} would additionally require {@code Secure}, which
     * over plain http on localhost means the browser drops the cookie outright
     * and the bell silently never binds.
     */
    public ResponseCookie cookiePour(String jeton) {
        return ResponseCookie.from(JetonAbonne.NOM_COOKIE, jeton)
                .httpOnly(true)
                .path("/")
                .maxAge(DUREE_COOKIE)
                .sameSite("Lax")
                .build();
    }
}
