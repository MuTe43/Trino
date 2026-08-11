package tn.sncft.trino.iam.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tn.sncft.trino.iam.domaine.Role;

/**
 * Request DTO for creating an account. No password field: the admin never
 * chooses one, a random one is generated server-side and returned once in
 * {@link UtilisateurCreeDTO}.
 */
public record UtilisateurCreateDTO(
        @NotBlank @Email @Size(max = 160) String email,
        @NotBlank @Size(max = 120) String nom,
        @NotNull Role role
) {
}
