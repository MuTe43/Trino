package tn.sncft.trino.iam.dto;

import tn.sncft.trino.iam.domaine.Role;

/**
 * Public view of a Utilisateur. Never exposes the password hash.
 */
public record UtilisateurDTO(
        Long id,
        String email,
        String nom,
        Role role,
        boolean actif
) {
}
