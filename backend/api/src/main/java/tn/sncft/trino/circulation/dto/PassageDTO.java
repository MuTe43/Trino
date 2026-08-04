package tn.sncft.trino.circulation.dto;

import tn.sncft.trino.circulation.domaine.ClasseRetard;

import java.time.OffsetDateTime;

/**
 * One stop of a course, carrying all three times of spec section 4.5 side by
 * side so the client never has to reconstruct one from another:
 *
 * <ul>
 *   <li>{@code *Theorique} -- the published timetable, the contract</li>
 *   <li>{@code *Estimee} / {@code *Estime} -- current prediction</li>
 *   <li>{@code *Reelle} / {@code *Reel} -- observed, null until passed</li>
 * </ul>
 *
 * <p>{@code franchi} is {@code arriveeReelle != null}. The client shows the
 * real time for a franchi stop and the estimate for the rest; it never adds
 * {@code retardMin} to a theoretical time itself, which is what keeps the map
 * panel, the station page and the kiosk board from drifting apart.
 */
public record PassageDTO(
        short ordre,
        GareBreveDTO gare,
        String quai,
        OffsetDateTime arriveeTheorique,
        OffsetDateTime arriveeEstimee,
        OffsetDateTime arriveeReelle,
        OffsetDateTime departTheorique,
        OffsetDateTime departEstime,
        OffsetDateTime departReel,
        int retardMin,
        ClasseRetard classeRetard,
        boolean franchi) {

    public record GareBreveDTO(Long id, String nom) {
    }
}
