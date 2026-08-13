package tn.sncft.trino.notification.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import tn.sncft.trino.exploitation.domaine.Gravite;
import tn.sncft.trino.notification.domaine.CanalType;
import tn.sncft.trino.notification.domaine.Evenement;

import java.util.Set;

/**
 * {@code POST /regles-alerte}.
 *
 * <p>{@code modifiePar} is not a field: it is the authenticated administrator,
 * taken from the security context. A caller naming who changed a rule is a
 * caller who can put someone else's name on their own decision.
 */
public record RegleAlerteCreateDTO(
        @NotNull(message = "obligatoire")
        Evenement evenement,

        // Required for RETARD_SEUIL and forbidden otherwise -- see
        // chk_regle_seuil, and ServiceRegleAlerte, which refuses the mismatch
        // with a message rather than letting the constraint surface as a 409.
        // The upper bound stops a typo (300 for 30) from creating a rule that
        // never fires and looks like a broken engine.
        @Positive(message = "doit être supérieur à 0")
        @Max(value = 240, message = "240 minutes maximum")
        Short seuilMin,

        Gravite graviteMin,

        @NotEmpty(message = "au moins un canal est requis")
        Set<CanalType> canaux,

        Boolean actif) {
}
