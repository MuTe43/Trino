package tn.sncft.trino.analytique.dto;

/**
 * How much of one train's programme on one ligne actually ran, over a range.
 *
 * <p>Availability here is defined as the share of scheduled courses that were
 * not cancelled. That is the only definition this schema can support honestly:
 * rolling stock carries no status and no downtime (invariant 1), so there is
 * nothing to read a maintenance window from. What {@code course} does record is
 * whether each dated run happened, and a train whose runs are cancelled is a
 * train that was not available.
 *
 * <p>Split per (train, ligne) rather than per train: a train that runs on two
 * lignes and is cancelled on only one is a fact about that ligne, and a single
 * combined figure would hide it.
 */
public record DisponibiliteTrainDTO(
        String trainNumero,
        String trainNom,
        String ligneNom,
        long coursesProgrammees,
        long coursesRealisees,
        long coursesAnnulees,
        double tauxDisponibilite) {
}
