package tn.sncft.trino.referentiel.evenement;

/**
 * Published when a gare changes, in particular its coordinates.
 *
 * <p>Stops are what a ligne's geometry is anchored to, so moving a gare
 * invalidates every derived geometry that serves it — the same failure
 * {@link LigneModifiee} guards against, arriving through the other input.
 */
public record GareModifiee(Long gareId) {
}
