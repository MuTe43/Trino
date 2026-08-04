package tn.sncft.trino.circulation.service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * One position fix as the engine sees it: the raw ping plus the chainage it
 * projects to. Held in memory by {@link EtatCirculationStore}, never persisted
 * (the append-only history is {@code position_course}).
 *
 * <p>{@code vitesseKmh} is the GROUND speed reported by the AVL hardware. It is
 * carried for display only and must never enter an ETA computation -- see
 * {@link CalculateurEta#vitesseChainageKmh}.
 */
public record FixPosition(
        OffsetDateTime horodatage,
        BigDecimal latitude,
        BigDecimal longitude,
        Short vitesseKmh,
        BigDecimal avancementKm) {
}
