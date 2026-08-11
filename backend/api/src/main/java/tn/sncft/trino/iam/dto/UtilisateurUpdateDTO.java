package tn.sncft.trino.iam.dto;

import jakarta.validation.constraints.Size;
import tn.sncft.trino.iam.domaine.Role;

/**
 * Request DTO for updating an account. PATCH semantics: every field is
 * optional, and a null value leaves the corresponding property unchanged.
 */
public record UtilisateurUpdateDTO(
        @Size(max = 120) String nom,
        Role role,
        Boolean actif
) {
}
