package tn.sncft.trino.analytique.dto;

/** One ligne's delay profile for a service date. */
public record RetardParLigneDTO(
        Long ligneId,
        String ligneNom,
        long courses,
        long coursesEnRetard,
        double retardMoyenMin,
        int retardMaxMin) {
}
