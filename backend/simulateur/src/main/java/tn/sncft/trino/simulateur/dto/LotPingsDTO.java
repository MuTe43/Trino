package tn.sncft.trino.simulateur.dto;

import java.util.List;

/** A tick's worth of positions, posted as one batch. */
public record LotPingsDTO(List<PingDTO> pings) {
}
