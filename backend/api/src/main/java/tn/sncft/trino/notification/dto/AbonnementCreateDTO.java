package tn.sncft.trino.notification.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import tn.sncft.trino.notification.domaine.CanalType;
import tn.sncft.trino.notification.domaine.CibleType;

import java.util.Set;

/**
 * {@code POST /abonnements}. Carries no identity: who this belongs to is the
 * caller's own credential, never a field of the request, or one subscriber
 * could create subscriptions in another's name.
 */
public record AbonnementCreateDTO(
        @NotNull(message = "obligatoire")
        CibleType cibleType,

        @NotNull(message = "obligatoire")
        @Positive(message = "doit être un identifiant positif")
        Long cibleId,

        @NotEmpty(message = "au moins un canal est requis")
        Set<CanalType> canaux,

        @Email(message = "adresse email invalide")
        @Size(max = 160, message = "160 caractères maximum")
        String email) {

    /**
     * The one cross-field rule: {@code EMAIL} needs somewhere to send.
     *
     * <p>Reported as a violation of {@code emailRequisPourCanalEmail} rather
     * than of {@code email}, because Jakarta Validation names the property it
     * was declared on. It is the only entry in {@code details[].champ} in the
     * whole API that is not a plain field path -- the form marks its email input
     * {@code required} whenever EMAIL is ticked, so a caller normally meets this
     * rule before the request is sent.
     */
    @AssertTrue(message = "une adresse email est obligatoire pour le canal EMAIL")
    public boolean isEmailRequisPourCanalEmail() {
        if (canaux == null || !canaux.contains(CanalType.EMAIL)) {
            return true;
        }
        return email != null && !email.isBlank();
    }
}
