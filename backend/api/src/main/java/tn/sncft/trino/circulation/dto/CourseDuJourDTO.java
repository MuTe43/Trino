package tn.sncft.trino.circulation.dto;

import tn.sncft.trino.circulation.domaine.SensCourse;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Everything the position source needs to know where a train should be: the
 * line's polyline, the stops with their chainage, and the theoretical times.
 * Nothing about how positions are produced leaks back the other way.
 */
public record CourseDuJourDTO(
        Long courseId,
        SensCourse sens,
        OffsetDateTime departTheorique,
        OffsetDateTime arriveeTheorique,
        BigDecimal avancementKm,
        TrainCourseDTO train,
        LigneCourseDTO ligne,
        List<List<Double>> trace,
        List<ArretCourseDTO> desserte
) {

    /** The ligne, trimmed to what a position producer needs. */
    public record LigneCourseDTO(
            Long id,
            String code,
            String nom,
            BigDecimal distanceKm,
            Short vitesseMaxKmh
    ) {
    }

    /** The rolling stock. No status, no delay -- those live on the course. */
    public record TrainCourseDTO(
            Long id,
            String numero,
            String nom,
            Short vitesseMaxKmh
    ) {
    }

    /**
     * One stop, already resolved for this course's direction: {@code pkKm} is
     * mirrored for a RETOUR, so a producer never has to know the desserte is
     * stored one way round.
     */
    public record ArretCourseDTO(
            Long gareId,
            String code,
            String nom,
            short ordre,
            BigDecimal pkKm,
            BigDecimal latitude,
            BigDecimal longitude,
            OffsetDateTime arriveeTheorique,
            OffsetDateTime departTheorique
    ) {
    }
}
