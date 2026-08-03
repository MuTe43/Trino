package tn.sncft.trino.commun.dto;

import java.util.List;

/**
 * Generic pagination envelope shared by all list endpoints, matching
 * docs/architecture/api-contract.md: {contenu, page, taille, total}.
 */
public record PageDTO<T>(List<T> contenu, int page, int taille, long total) {
}
