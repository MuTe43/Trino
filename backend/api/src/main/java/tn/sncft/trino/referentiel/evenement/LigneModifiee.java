package tn.sncft.trino.referentiel.evenement;

/**
 * Published when a ligne's definition changes, in particular its {@code trace}.
 *
 * <p>Anything caching something derived from a ligne has to drop it. An event
 * rather than a direct call so référentiel stays unaware of who is listening:
 * modules talk through interfaces, not through each other's internals
 * (decision 1).
 */
public record LigneModifiee(Long ligneId) {
}
