package tn.sncft.trino.circulation.dto;

import tn.sncft.trino.circulation.domaine.CauseRetard;
import tn.sncft.trino.circulation.domaine.ClasseRetard;
import tn.sncft.trino.circulation.domaine.SensCourse;
import tn.sncft.trino.circulation.domaine.StatutCourse;
import tn.sncft.trino.referentiel.domaine.TypeTrain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * A course as every list and map panel shows it. The train's identity is
 * flattened in ({@code numeroTrain}, {@code nomTrain}, {@code type}) because a
 * Train is rolling stock and carries no status of its own -- the status and the
 * delay on this record belong to the run, not to the locomotive.
 *
 * <p>{@code position}, {@code garePrecedente}, {@code gareSuivante} and
 * {@code etaSuivante} come from hot state and are null for a course that has
 * not yet reported.
 */
public record CourseResumeDTO(
        Long id,
        String numeroTrain,
        String nomTrain,
        TypeTrain type,
        LigneBreveDTO ligne,
        SensCourse sens,
        StatutCourse statut,
        int retardMin,
        ClasseRetard classeRetard,
        CauseRetard causeRetard,
        OffsetDateTime departTheorique,
        OffsetDateTime arriveeTheorique,
        PositionCouranteDTO position,
        GareBreveDTO garePrecedente,
        GareBreveDTO gareSuivante,
        OffsetDateTime etaSuivante) {

    public record LigneBreveDTO(Long id, String nom) {
    }

    public record GareBreveDTO(Long id, String nom) {
    }

    /** Ground speed, for display only -- never used in an ETA. */
    public record PositionCouranteDTO(BigDecimal latitude, BigDecimal longitude, Short vitesseKmh) {
    }
}
