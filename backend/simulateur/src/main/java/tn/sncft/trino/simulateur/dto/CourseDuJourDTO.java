package tn.sncft.trino.simulateur.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Mirror of the API's courses-du-jour payload. Deliberately a separate copy
 * rather than a shared module: the HTTP contract is the only thing that should
 * couple these two processes, and real AVL hardware would not be compiling
 * against Trino's classes either.
 *
 * <p>Unknown fields are ignored so the API can add to the payload without
 * breaking a producer that has not been redeployed.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CourseDuJourDTO(
        Long courseId,
        String sens,
        OffsetDateTime departTheorique,
        OffsetDateTime arriveeTheorique,
        Double avancementKm,
        TrainDTO train,
        LigneDTO ligne,
        List<List<Double>> trace,
        List<ArretDTO> desserte
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LigneDTO(Long id, String code, String nom, Double distanceKm, Integer vitesseMaxKmh) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TrainDTO(Long id, String numero, String nom, Integer vitesseMaxKmh) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ArretDTO(
            Long gareId,
            String code,
            String nom,
            Integer ordre,
            Double pkKm,
            Double latitude,
            Double longitude,
            OffsetDateTime arriveeTheorique,
            OffsetDateTime departTheorique
    ) {
    }
}
