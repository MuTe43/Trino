package tn.sncft.trino.iam.dto;

import java.time.OffsetDateTime;

/**
 * A row of the login audit trail, for the administration console.
 */
public record JournalConnexionDTO(
        Long id,
        Long utilisateurId,
        String utilisateurNom,
        String emailTente,
        String adresseIp,
        String userAgent,
        boolean succes,
        OffsetDateTime horodatage
) {
}
