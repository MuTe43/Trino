package tn.sncft.trino.circulation.domaine;

/**
 * Direction of a course along its ligne. The desserte is stored once, in the
 * ALLER direction; a RETOUR course walks it mirrored.
 */
public enum SensCourse {
    ALLER,
    RETOUR
}
