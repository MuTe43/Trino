package tn.sncft.trino.iam.dto;

/**
 * Request DTO for {@code POST /api/v1/auth/refresh}. Optional: browser
 * clients rely on the httpOnly {@code refreshToken} cookie instead (they
 * cannot read its value to put it here); non-browser API clients may supply
 * it in the body.
 */
public record RefreshRequestDTO(
        String refreshToken
) {
}
