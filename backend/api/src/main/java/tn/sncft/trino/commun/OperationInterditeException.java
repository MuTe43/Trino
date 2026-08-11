package tn.sncft.trino.commun;

/**
 * Thrown when an operation is refused on this route for every caller, whatever
 * their role. Mapped to HTTP 403 {@code ACCES_REFUSE} by
 * {@link ApiExceptionHandler}, keeping its own message.
 *
 * <p>The case it exists for: {@code PATCH /incidents/{id}} carrying
 * {@code statut: RESOLU}. Resolution has its own endpoint, so the refusal is
 * not a role check -- a responsable is refused here exactly as an agent is --
 * and the message has to be able to say where to go instead. Spring's own
 * {@code AccessDeniedException} is answered with a fixed "Accès refusé.", which
 * would leave the caller with a 403 and no idea that the operation exists
 * elsewhere.
 */
public class OperationInterditeException extends RuntimeException {

    public OperationInterditeException(String message) {
        super(message);
    }
}
