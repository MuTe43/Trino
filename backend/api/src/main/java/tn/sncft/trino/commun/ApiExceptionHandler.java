package tn.sncft.trino.commun;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.connector.ClientAbortException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Centralized exception handling. Every error response produced by the API
 * goes through here and follows the envelope defined in
 * docs/architecture/api-contract.md. Replaces Spring Boot's default
 * whitelabel error page entirely.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(RessourceIntrouvableException.class)
    public ResponseEntity<ErreurDTO> gererRessourceIntrouvable(RessourceIntrouvableException ex) {
        return reponse(HttpStatus.NOT_FOUND, "INTROUVABLE", ex.getMessage(), List.of());
    }

    @ExceptionHandler(AuthentificationEchoueeException.class)
    public ResponseEntity<ErreurDTO> gererAuthentificationEchouee(AuthentificationEchoueeException ex) {
        return reponse(HttpStatus.UNAUTHORIZED, "NON_AUTHENTIFIE", ex.getMessage(), List.of());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErreurDTO> gererAccesRefuse(AccessDeniedException ex) {
        return reponse(HttpStatus.FORBIDDEN, "ACCES_REFUSE", "Accès refusé.", List.of());
    }

    /**
     * Refused for everyone on this route, whatever the caller's role -- so the
     * message is kept, unlike the {@link AccessDeniedException} branch above.
     * It is the only thing that can tell the caller the operation exists on
     * another endpoint.
     */
    @ExceptionHandler(OperationInterditeException.class)
    public ResponseEntity<ErreurDTO> gererOperationInterdite(OperationInterditeException ex) {
        return reponse(HttpStatus.FORBIDDEN, "ACCES_REFUSE", ex.getMessage(), List.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErreurDTO> gererRessourceNonTrouvee(NoResourceFoundException ex) {
        return reponse(HttpStatus.NOT_FOUND, "INTROUVABLE", "Ressource introuvable.", List.of());
    }

    /**
     * Field errors are the whole story in practice: Spring reports a violation
     * on a collection element as an indexed FieldError (`trace[0]`,
     * `pings[0].latitude`), not as a global error, so `details[].champ` stays a
     * real field path as the contract promises.
     *
     * <p>The fallback covers the one case that is not a field error: a
     * class-level constraint, whose property path is empty. There are none
     * today; without the fallback, adding one later would silently produce a
     * 400 with no details at all.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErreurDTO> gererValidation(MethodArgumentNotValidException ex) {
        List<ErreurDTO.DetailErreurDTO> details = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ErreurDTO.DetailErreurDTO(fe.getField(), fe.getDefaultMessage()))
                .toList();
        if (details.isEmpty()) {
            details = ex.getBindingResult().getGlobalErrors().stream()
                    .map(oe -> new ErreurDTO.DetailErreurDTO(oe.getObjectName(), oe.getDefaultMessage()))
                    .toList();
        }
        return reponse(HttpStatus.BAD_REQUEST, "VALIDATION_ECHOUEE", "La requête est invalide.", details);
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class,
            IllegalArgumentException.class})
    public ResponseEntity<ErreurDTO> gererRequeteMalformee(Exception ex) {
        // Client errors are not logged at WARN -- a caller sending rubbish is
        // not an incident. But with no detail in the envelope either, a 400
        // from this branch is otherwise undiagnosable from both ends.
        log.debug("Requête malformée : {} - {}", ex.getClass().getName(), ex.getMessage());
        return reponse(HttpStatus.BAD_REQUEST, "VALIDATION_ECHOUEE", "La requête est invalide.", List.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErreurDTO> gererMethodeNonSupportee(HttpRequestMethodNotSupportedException ex) {
        return reponse(HttpStatus.METHOD_NOT_ALLOWED, "VALIDATION_ECHOUEE",
                "Méthode HTTP non supportée pour cette ressource.", List.of());
    }

    /**
     * Over a rate limit. {@code 429} with the message kept, unlike the generic
     * 400 branch: "réessayez dans une minute" is the only part a caller can act
     * on, and a rate limit that does not say when to come back is answered by
     * retrying at once.
     */
    @ExceptionHandler(TropDeRequetesException.class)
    public ResponseEntity<ErreurDTO> gererTropDeRequetes(TropDeRequetesException ex) {
        return reponse(HttpStatus.TOO_MANY_REQUESTS, "TROP_DE_REQUETES", ex.getMessage(), List.of());
    }

    /** A transition the domain refuses, caught before the database sees it. */
    @ExceptionHandler(ConflitException.class)
    public ResponseEntity<ErreurDTO> gererConflitMetier(ConflitException ex) {
        return reponse(HttpStatus.CONFLICT, "CONFLIT", ex.getMessage(), List.of());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErreurDTO> gererConflit(DataIntegrityViolationException ex) {
        return reponse(HttpStatus.CONFLICT, "CONFLIT", "Conflit : cette opération viole une contrainte d'unicité.",
                List.of());
    }

    /**
     * A browser {@code EventSource} disconnecting mid-stream (navigation, tab
     * close) unwinds through here on every SSE subscription -- {@link
     * tn.sncft.trino.diffusion.HubSse} already handles the same exceptions
     * around an explicit {@code send()}, but the container can also raise them
     * while completing the async dispatch itself, outside that path. Neither
     * case is an incident, and by the time either fires the
     * {@code text/event-stream} response is already committed, so there is no
     * body left to write -- attempting one is exactly the second failure this
     * handler exists to avoid.
     */
    @ExceptionHandler({AsyncRequestNotUsableException.class, ClientAbortException.class})
    public void gererDeconnexionClient(Exception ex) {
        log.debug("Flux interrompu par le client : {} - {}", ex.getClass().getSimpleName(), ex.getMessage());
    }

    /**
     * The Windows counterpart of {@link #gererDeconnexionClient}: a client
     * that vanishes mid async dispatch (SSE navigation away, tab close) can
     * surface here as a bare {@link IOException}, with neither of the two
     * more specific types above -- those are matched first when they do
     * apply, since Spring picks the closest exception match.
     *
     * <p>Deliberately not a blanket handler: it only takes the DEBUG-and-drop
     * path when {@code response.isCommitted()} or {@code
     * request.isAsyncStarted()}, i.e. exactly the case where a body cannot
     * usefully be written back anyway (the {@code text/event-stream} headers
     * are already sent, or the client that would receive it is gone). A
     * genuine {@link IOException} on an ordinary, not-yet-committed,
     * synchronous response -- a real failure worth seeing -- still falls
     * through to the ERROR branch and the normal {@link ErreurDTO} body.
     */
    @ExceptionHandler(IOException.class)
    public ResponseEntity<ErreurDTO> gererErreurEs(IOException ex, HttpServletRequest request,
                                                    HttpServletResponse response) {
        if (response.isCommitted() || request.isAsyncStarted()) {
            log.debug("E/S interrompue par une déconnexion client (réponse déjà engagée) : {} - {}",
                    ex.getClass().getSimpleName(), ex.getMessage());
            return null;
        }
        log.error("Erreur d'E/S non gérée", ex);
        return reponse(HttpStatus.INTERNAL_SERVER_ERROR, "ERREUR_INTERNE", "Une erreur interne est survenue.",
                List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErreurDTO> gererErreurInterne(Exception ex) {
        log.error("Erreur interne non gérée", ex);
        return reponse(HttpStatus.INTERNAL_SERVER_ERROR, "ERREUR_INTERNE", "Une erreur interne est survenue.",
                List.of());
    }

    private ResponseEntity<ErreurDTO> reponse(HttpStatus statut, String code, String message,
                                               List<ErreurDTO.DetailErreurDTO> details) {
        ErreurDTO erreur = new ErreurDTO(OffsetDateTime.now(ZoneOffset.UTC), statut.value(), code, message, details);
        return ResponseEntity.status(statut).body(erreur);
    }
}
