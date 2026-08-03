package tn.sncft.trino.commun;

/**
 * Thrown when a login attempt or a refresh-token exchange fails (unknown
 * email, wrong password, missing/revoked/expired refresh token). Mapped to
 * HTTP 401 with code NON_AUTHENTIFIE by {@link ApiExceptionHandler}.
 */
public class AuthentificationEchoueeException extends RuntimeException {

    public AuthentificationEchoueeException(String message) {
        super(message);
    }
}
