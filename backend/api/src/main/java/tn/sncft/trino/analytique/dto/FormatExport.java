package tn.sncft.trino.analytique.dto;

import java.util.Locale;

/** The two export formats. PDF is out of scope for phase 5. */
public enum FormatExport {

    CSV("csv", "text/csv; charset=UTF-8"),
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final String extension;
    private final String typeContenu;

    FormatExport(String extension, String typeContenu) {
        this.extension = extension;
        this.typeContenu = typeContenu;
    }

    public String extension() {
        return extension;
    }

    public String typeContenu() {
        return typeContenu;
    }

    /** Accepts {@code csv} or {@code CSV}; anything else is a 400. */
    public static FormatExport depuis(String valeur) {
        if (valeur == null || valeur.isBlank()) {
            return CSV;
        }
        try {
            return valueOf(valeur.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Format inconnu : " + valeur + " (csv ou xlsx).");
        }
    }
}
