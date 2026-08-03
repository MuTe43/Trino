package tn.sncft.trino.commun;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErreurDTO> gererRessourceNonTrouvee(NoResourceFoundException ex) {
        return reponse(HttpStatus.NOT_FOUND, "INTROUVABLE", "Ressource introuvable.", List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErreurDTO> gererValidation(MethodArgumentNotValidException ex) {
        List<ErreurDTO.DetailErreurDTO> details = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ErreurDTO.DetailErreurDTO(fe.getField(), fe.getDefaultMessage()))
                .toList();
        return reponse(HttpStatus.BAD_REQUEST, "VALIDATION_ECHOUEE", "La requête est invalide.", details);
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class,
            IllegalArgumentException.class})
    public ResponseEntity<ErreurDTO> gererRequeteMalformee(Exception ex) {
        return reponse(HttpStatus.BAD_REQUEST, "VALIDATION_ECHOUEE", "La requête est invalide.", List.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErreurDTO> gererMethodeNonSupportee(HttpRequestMethodNotSupportedException ex) {
        return reponse(HttpStatus.METHOD_NOT_ALLOWED, "VALIDATION_ECHOUEE",
                "Méthode HTTP non supportée pour cette ressource.", List.of());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErreurDTO> gererConflit(DataIntegrityViolationException ex) {
        return reponse(HttpStatus.CONFLICT, "CONFLIT", "Conflit : cette opération viole une contrainte d'unicité.",
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
