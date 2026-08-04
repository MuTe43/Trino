package tn.sncft.trino.simulateur.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** What the ingestion endpoint reports back about a batch. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ResultatIngestionDTO(int acceptes, int rejetes) {
}
