package tn.sncft.trino.analytique.dto;

import java.time.LocalDate;

/**
 * One service date's operational figures, the payload of
 * {@code GET /tableau-bord/kpi?date=}.
 *
 * <p>A single day carries about ±5 points of run-to-run noise on punctuality
 * (measured in phase 2), so this is an operational view. The figure that gets
 * presented comes from a range -- see {@link PointPonctualiteDTO}.
 *
 * @param trainsEnCirculation courses scheduled that day that were not cancelled:
 *                            trains that ran, or are running. Not a count of
 *                            trains moving at this instant -- that reading would
 *                            make every historical date report zero and the card
 *                            useless for anything but today.
 * @param nbRetards           courses 5+ minutes late. Five, not one, because the
 *                            project classifies anything under five minutes as
 *                            A_L_HEURE (domain-model.md) and the punctuality rate
 *                            below uses the same cut.
 * @param retardMoyenMin      mean delay over courses that were late at all
 *                            ({@code retard_min > 0}), so on-time runs do not
 *                            dilute it to meaninglessness
 * @param tauxPonctualite     share of stops actually reached that were served
 *                            under five minutes late, in [0, 1]
 * @param passagesMesures     how many stops that rate was computed over. Zero
 *                            early in a service day, when nothing has been
 *                            reached yet and a rate of "0" would read as total
 *                            failure rather than "no data".
 * @param voyageursImpactes   ESTIMATE: sum of train capacity over delayed
 *                            courses. Modelled, never measured -- the UI has to
 *                            say so.
 */
public record KpiJourDTO(
        LocalDate date,
        long trainsEnCirculation,
        long nbRetards,
        double retardMoyenMin,
        double tauxPonctualite,
        long passagesMesures,
        long incidentsOuverts,
        long incidentsResolus,
        long trainsAnnules,
        long voyageursImpactes) {
}
