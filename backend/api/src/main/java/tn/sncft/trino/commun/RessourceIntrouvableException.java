package tn.sncft.trino.commun;

/**
 * Thrown when a requested entity does not exist. Mapped to HTTP 404 by
 * {@link ApiExceptionHandler}.
 */
public class RessourceIntrouvableException extends RuntimeException {

    public RessourceIntrouvableException(String message) {
        super(message);
    }
}
