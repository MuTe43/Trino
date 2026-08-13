package tn.sncft.trino.notification.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import tn.sncft.trino.exploitation.domaine.Gravite;
import tn.sncft.trino.notification.domaine.CanalType;

import java.util.Set;

/**
 * {@code PATCH /regles-alerte/{id}} -- a partial update: an absent field means
 * unchanged.
 *
 * <p>{@code evenement} is not here and cannot be edited. Changing which event a
 * rule reacts to is not an edit, it is a different rule -- and it would silently
 * re-point every notification the console shows as produced by this one.
 * Deactivate it and create the other.
 *
 * <p>Which means {@code seuilMin} carries an asymmetry worth naming: sending it
 * as null on a {@code RETARD_SEUIL} rule leaves the threshold alone rather than
 * clearing it, because {@code chk_regle_seuil} forbids that row from having none
 * at all. There is no request that can empty it.
 */
public record RegleAlerteUpdateDTO(
        @Positive(message = "doit être supérieur à 0")
        @Max(value = 240, message = "240 minutes maximum")
        Short seuilMin,

        Gravite graviteMin,

        /*
         * "Toutes les gravités" — the one field a PATCH must be able to empty.
         *
         * An absent field means unchanged, so a null `graviteMin` cannot express
         * "clear it"; the two are the same JSON. The console offers a "Toutes"
         * option that maps to null, so without this flag choosing it returned
         * 200 with the old severity intact — the dialog closed, the table
         * reloaded, and nothing had changed. An explicit flag is uglier than a
         * bare null and is the only shape that says which of the two was meant.
         */
        Boolean effacerGraviteMin,

        // @Size, not @NotEmpty: absent means unchanged on a PATCH, and
        // @NotEmpty rejects null -- which would make `canaux` mandatory on
        // every partial update, including one that only flips `actif`. An
        // explicitly empty list is still refused: a rule with no channel is a
        // rule that silently notifies nobody.
        @Size(min = 1, message = "au moins un canal est requis")
        Set<CanalType> canaux,

        Boolean actif) {
}
