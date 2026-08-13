package tn.sncft.trino.notification.domaine;

/**
 * Where one emitted notification got to.
 *
 * <p>{@code ECHEC} is a first-class outcome, not an exception swallowed
 * somewhere: a channel that is down must leave a row saying so, with its
 * {@code erreur} populated, rather than disappear.
 */
public enum StatutNotification {
    EN_ATTENTE,
    ENVOYE,
    ECHEC
}
