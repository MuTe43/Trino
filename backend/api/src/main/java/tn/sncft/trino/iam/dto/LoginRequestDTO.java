package tn.sncft.trino.iam.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request DTO for {@code POST /api/v1/auth/login}.
 */
public record LoginRequestDTO(
        @NotBlank String email,
        @NotBlank String motDePasse
) {
}
