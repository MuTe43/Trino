package tn.sncft.trino.iam.dto;

import tn.sncft.trino.iam.domaine.Role;

/**
 * Returned only by account creation and by password re-issue, never by any
 * other endpoint. {@code motDePasseInitial} carries the plaintext generated
 * password: it exists nowhere else, is never stored, and cannot be read
 * again once this response has been sent -- only the BCrypt hash survives
 * server-side.
 */
public record UtilisateurCreeDTO(
        Long id,
        String email,
        String nom,
        Role role,
        boolean actif,
        String motDePasseInitial
) {
}
