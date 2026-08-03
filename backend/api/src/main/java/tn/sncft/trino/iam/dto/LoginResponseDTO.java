package tn.sncft.trino.iam.dto;

/**
 * Response DTO for login and refresh, matching docs/architecture/api-contract.md.
 */
public record LoginResponseDTO(
        String accessToken,
        String refreshToken,
        UtilisateurDTO utilisateur
) {
}
