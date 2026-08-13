package tn.sncft.trino.notification.dto;

import tn.sncft.trino.notification.domaine.CanalType;
import tn.sncft.trino.notification.domaine.CibleType;

import java.time.OffsetDateTime;
import java.util.Set;

/**
 * One subscription, as its owner sees it.
 *
 * <p>Neither {@code jetonAnonyme} nor {@code utilisateurId} appears: the token
 * is a bearer credential that never travels in a response body, and the account
 * id would tell the reader nothing they did not already know about themselves.
 */
public record AbonnementDTO(
        Long id,
        CibleType cibleType,
        Long cibleId,
        Set<CanalType> canaux,
        String email,
        OffsetDateTime creeAt) {
}
