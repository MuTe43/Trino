package tn.sncft.trino.circulation.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * A batch of positions. Batched on purpose: one insert per ping would put the
 * ingestion path at the mercy of the feed's tick rate.
 */
public record LotPingsDTO(
        @NotNull(message = "obligatoire")
        @Size(max = 500, message = "500 pings maximum par lot")
        @Valid
        List<PingDTO> pings
) {
}
