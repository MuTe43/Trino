package tn.sncft.trino.circulation.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * One GPS fix from the position source. Deliberately shaped like what real AVL
 * hardware emits -- coordinates and a speed, never a chainage. Turning a fix
 * into an avancement_km is the API's job.
 */
public record PingDTO(
        @NotNull(message = "obligatoire")
        Long courseId,

        @NotNull(message = "obligatoire")
        OffsetDateTime horodatage,

        @NotNull(message = "obligatoire")
        @DecimalMin(value = "-90.0", message = "latitude hors bornes")
        @DecimalMax(value = "90.0", message = "latitude hors bornes")
        BigDecimal latitude,

        @NotNull(message = "obligatoire")
        @DecimalMin(value = "-180.0", message = "longitude hors bornes")
        @DecimalMax(value = "180.0", message = "longitude hors bornes")
        BigDecimal longitude,

        Short vitesseKmh
) {
}
