package tn.sncft.trino.securite;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;
import tn.sncft.trino.iam.dto.UtilisateurDTO;
import tn.sncft.trino.iam.service.JetonService;
import tn.sncft.trino.iam.service.UtilisateurService;

import java.io.IOException;

/**
 * Reads the Authorization header, validates the JWT and (if valid) sets the
 * authentication in the security context. A missing or invalid token is a
 * no-op: the filter just lets the request through as anonymous, and the
 * access rules in ConfigurationSecurite decide whether that is acceptable.
 */
public class FiltreJwt extends OncePerRequestFilter {

    private final JetonService jetonService;
    private final UtilisateurService utilisateurService;

    public FiltreJwt(JetonService jetonService, UtilisateurService utilisateurService) {
        this.jetonService = jetonService;
        this.utilisateurService = utilisateurService;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                     @NonNull FilterChain chain) throws ServletException, IOException {
        String entete = request.getHeader("Authorization");
        if (entete != null && entete.startsWith("Bearer ")) {
            String jeton = entete.substring(7);
            if (jetonService.estValide(jeton)) {
                try {
                    String email = jetonService.extraireEmail(jeton);
                    UtilisateurDTO utilisateur = utilisateurService.moi(email);
                    if (utilisateur.actif()) {
                        DetailsUtilisateur details = new DetailsUtilisateur(utilisateur);
                        UsernamePasswordAuthenticationToken authentication =
                                new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities());
                        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                    }
                } catch (RuntimeException ex) {
                    // Stale token (e.g. deleted user) or malformed claims: leave the
                    // request anonymous, do not fail the filter chain.
                    SecurityContextHolder.clearContext();
                }
            }
        }
        chain.doFilter(request, response);
    }
}
