package tn.sncft.trino.analytique.dto;

import java.util.List;

/**
 * A report reduced to a header row and value rows, which is all either export
 * format needs.
 *
 * <p>Going through this shape rather than serialising each report type
 * separately is what keeps {@code ServiceExport} generic: adding the incidents
 * report in phase 6 is one builder method and one registry entry, with no new
 * CSV or XLSX code.
 */
public record TableauRapport(String nom, List<String> entetes, List<List<Object>> lignes) {
}
