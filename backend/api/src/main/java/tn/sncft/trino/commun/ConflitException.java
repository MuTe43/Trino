package tn.sncft.trino.commun;

/**
 * Thrown when an operation is legal in shape but not from the state the
 * resource is in -- an incident transition the state machine forbids, notably.
 * Mapped to HTTP 409 {@code CONFLIT} by {@link ApiExceptionHandler}.
 *
 * <p>Distinct from the {@code DataIntegrityViolationException} branch, which
 * reports the same code for a constraint the database rejected. This one is
 * refused before it reaches the database, and carries a message saying which
 * transition was attempted.
 */
public class ConflitException extends RuntimeException {

    public ConflitException(String message) {
        super(message);
    }
}
