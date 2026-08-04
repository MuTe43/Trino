package tn.sncft.trino.securite;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tn.sncft.trino.commun.ErreurDTO;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Guards {@code /api/v1/ingest/**} with a shared key in {@code X-Ingest-Key}.
 *
 * <p>The position source authenticates with this and nothing else: it is not a
 * user, so giving it a JWT and a role would model it as one. Replacing the
 * simulator with real AVL hardware is then a matter of handing over the key.
 *
 * <p>Rejecting here, in the filter chain, is deliberate. Bean validation on the
 * request body runs during controller argument resolution -- later than this --
 * so a keyless caller sending a malformed batch still gets 401 and learns
 * nothing about the payload shape of an endpoint it may not touch.
 */
public class FiltreCleIngestion extends OncePerRequestFilter {

    private static final String CHEMIN_PROTEGE = "/api/v1/ingest/";
    private static final String ENTETE = "X-Ingest-Key";

    private final byte[] cleAttendue;
    private final ObjectMapper objectMapper;

    public FiltreCleIngestion(String cleAttendue, ObjectMapper objectMapper) {
        this.cleAttendue = cleAttendue == null ? new byte[0] : cleAttendue.getBytes(StandardCharsets.UTF_8);
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                     @NonNull FilterChain chain) throws ServletException, IOException {
        if (!request.getRequestURI().startsWith(CHEMIN_PROTEGE)) {
            chain.doFilter(request, response);
            return;
        }

        String fournie = request.getHeader(ENTETE);
        if (fournie == null || !cleValide(fournie)) {
            ecrireErreur(response, "Clé d'ingestion absente ou invalide.");
            return;
        }

        // A valid key authenticates the feed itself, not a person. The
        // authority exists so the URL rule has something to match on.
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                "source-positions", null, List.of(new SimpleGrantedAuthority("ROLE_INGESTION")));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /** Constant-time compare: a shared secret must not leak through timing. */
    private boolean cleValide(String fournie) {
        return cleAttendue.length > 0
                && MessageDigest.isEqual(cleAttendue, fournie.getBytes(StandardCharsets.UTF_8));
    }

    private void ecrireErreur(HttpServletResponse response, String message) throws IOException {
        ErreurDTO erreur = new ErreurDTO(OffsetDateTime.now(ZoneOffset.UTC), HttpStatus.UNAUTHORIZED.value(),
                "CLE_INGESTION_INVALIDE", message, List.of());
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(erreur));
    }
}
