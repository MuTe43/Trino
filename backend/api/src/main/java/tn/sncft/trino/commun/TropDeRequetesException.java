package tn.sncft.trino.commun;

/**
 * The caller went over a rate limit. Answered as {@code 429} with code
 * {@code TROP_DE_REQUETES}.
 *
 * <p>A code of its own rather than folding into {@code VALIDATION_ECHOUEE}: the
 * request was not invalid, and a client told its payload was wrong will fix the
 * payload and try again immediately, which is the one thing that must not
 * happen here.
 */
public class TropDeRequetesException extends RuntimeException {

    public TropDeRequetesException(String message) {
        super(message);
    }
}
