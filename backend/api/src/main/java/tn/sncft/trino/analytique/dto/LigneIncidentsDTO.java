package tn.sncft.trino.analytique.dto;

import tn.sncft.trino.exploitation.domaine.Gravite;
import tn.sncft.trino.exploitation.domaine.TypeIncident;

/**
 * One row of the incidents report: a type and gravité bucket over the window.
 *
 * @param delaiResolutionMoyenH mean hours from {@code survenuAt} to
 *        {@code resoluAt}, over the resolved ones only. <b>Null</b> when none
 *        in the bucket has been resolved yet -- an average over no rows. Zero
 *        would read as "resolved instantly", so the UI renders "—" instead.
 */
public record LigneIncidentsDTO(
        TypeIncident type,
        Gravite gravite,
        long total,
        long resolus,
        Double delaiResolutionMoyenH) {
}
