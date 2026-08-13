package tn.sncft.trino.notification.dto;

import tn.sncft.trino.exploitation.domaine.Gravite;
import tn.sncft.trino.notification.domaine.CanalType;
import tn.sncft.trino.notification.domaine.Evenement;

import java.util.Set;

/**
 * One alert rule, as the administration console shows it.
 *
 * @param modifiePar     id of the last administrator to change it, null for a
 *                       rule still as V8 seeded it -- nobody has decided
 *                       anything about it yet, and putting a name there would
 *                       attribute a choice that was never made
 * @param modifieParNom  that administrator's name, resolved server-side so the
 *                       table does not have to fetch an account per row
 */
public record RegleAlerteDTO(
        Long id,
        Evenement evenement,
        Short seuilMin,
        Gravite graviteMin,
        Set<CanalType> canaux,
        boolean actif,
        Long modifiePar,
        String modifieParNom) {
}
