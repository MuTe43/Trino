package tn.sncft.trino.iam.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tn.sncft.trino.iam.dto.LoginRequestDTO;
import tn.sncft.trino.iam.dto.LoginResponseDTO;
import tn.sncft.trino.iam.dto.RefreshRequestDTO;
import tn.sncft.trino.iam.dto.UtilisateurDTO;
import tn.sncft.trino.iam.service.UtilisateurService;

import java.time.Duration;

/**
 * REST endpoints for authentication. Holds no business logic, only maps
 * requests to the service layer and sets the httpOnly refresh cookie.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final String NOM_COOKIE_REFRESH = "refreshToken";
    // Path=/ (not /api/v1/auth): the frontend's Next.js middleware, running
    // for its own /admin and /exploitation pages on a different port, must
    // also see this cookie to gate those routes. Cookie domain-matching
    // ignores port (both are host "localhost"), but path-matching is exact,
    // so scoping it to the auth path alone would hide it from every other
    // route on the frontend and break the middleware gate entirely.
    private static final String CHEMIN_COOKIE_REFRESH = "/";

    private final UtilisateurService utilisateurService;
    private final long refreshExpirationDays;

    public AuthController(UtilisateurService utilisateurService,
                           @Value("${trino.jwt.refresh-expiration-days}") long refreshExpirationDays) {
        this.utilisateurService = utilisateurService;
        this.refreshExpirationDays = refreshExpirationDays;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponseDTO> login(@Valid @RequestBody LoginRequestDTO requete,
                                                    HttpServletRequest httpRequete) {
        LoginResponseDTO reponse = utilisateurService.login(requete, httpRequete);
        return avecCookieRefresh(reponse, reponse.refreshToken());
    }

    @PostMapping("/refresh")
    public ResponseEntity<LoginResponseDTO> refresh(
            @CookieValue(name = NOM_COOKIE_REFRESH, required = false) String refreshTokenCookie,
            @RequestBody(required = false) RefreshRequestDTO requete) {
        LoginResponseDTO reponse = utilisateurService.rafraichir(
                requete != null ? requete.refreshToken() : null, refreshTokenCookie);
        return avecCookieRefresh(reponse, reponse.refreshToken());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(name = NOM_COOKIE_REFRESH, required = false) String refreshToken) {
        if (refreshToken != null) {
            utilisateurService.deconnexion(refreshToken);
        }
        ResponseCookie cookieExpiree = ResponseCookie.from(NOM_COOKIE_REFRESH, "")
                .httpOnly(true)
                .sameSite("Lax")
                .path(CHEMIN_COOKIE_REFRESH)
                .maxAge(0)
                .build();
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookieExpiree.toString())
                .build();
    }

    @GetMapping("/me")
    public UtilisateurDTO me(Authentication authentication) {
        return utilisateurService.moi(authentication.getName());
    }

    private ResponseEntity<LoginResponseDTO> avecCookieRefresh(LoginResponseDTO reponse, String refreshTokenBrut) {
        ResponseCookie cookie = ResponseCookie.from(NOM_COOKIE_REFRESH, refreshTokenBrut)
                .httpOnly(true)
                .sameSite("Lax")
                .path(CHEMIN_COOKIE_REFRESH)
                .maxAge(Duration.ofDays(refreshExpirationDays))
                .build();
        return ResponseEntity.status(HttpStatus.OK)
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(reponse);
    }
}
