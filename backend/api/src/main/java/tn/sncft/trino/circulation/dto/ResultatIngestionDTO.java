package tn.sncft.trino.circulation.dto;

/**
 * Outcome of a batch. A rejected ping is not an error: a course that has
 * reached its terminus or been cancelled simply stops accepting positions, and
 * the producer is told how many were dropped rather than failing the request.
 */
public record ResultatIngestionDTO(int acceptes, int rejetes) {
}
