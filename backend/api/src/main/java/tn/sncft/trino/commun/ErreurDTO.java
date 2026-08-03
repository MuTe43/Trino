package tn.sncft.trino.commun;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Standard error envelope returned by {@link ApiExceptionHandler} for every
 * 4xx/5xx response, matching docs/architecture/api-contract.md.
 */
public record ErreurDTO(
        OffsetDateTime horodatage,
        int statut,
        String code,
        String message,
        List<DetailErreurDTO> details
) {

    public record DetailErreurDTO(String champ, String probleme) {
    }
}
