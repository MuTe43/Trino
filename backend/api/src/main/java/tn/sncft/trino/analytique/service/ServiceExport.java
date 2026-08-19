package tn.sncft.trino.analytique.service;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.sncft.trino.analytique.dto.DisponibiliteTrainDTO;
import tn.sncft.trino.analytique.dto.FormatExport;
import tn.sncft.trino.analytique.dto.Granularite;
import tn.sncft.trino.analytique.dto.LigneIncidentsDTO;
import tn.sncft.trino.analytique.dto.PointPonctualiteDTO;
import tn.sncft.trino.analytique.dto.RetardParGareDTO;
import tn.sncft.trino.analytique.dto.RetardParLigneDTO;
import tn.sncft.trino.analytique.dto.TableauRapport;
import tn.sncft.trino.analytique.repository.AnalytiqueRepository;
import tn.sncft.trino.commun.PlageDates;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Builds a report and writes it as CSV or XLSX.
 *
 * <p>Generic over the report name by design: reports are registered in a map
 * from name to builder, so phase 6's {@code incidents} report is one builder
 * method and one entry, with no new serialisation code.
 */
@Service
public class ServiceExport {

    /**
     * Byte-order mark. Without it Excel on a French Windows locale reads a
     * UTF-8 file as Windows-1252 and every accented station name arrives
     * mojibaked -- "Béja" as "BÃ©ja". It is three bytes and it is the
     * difference between a supervisor double-clicking the file and it working,
     * or not.
     *
     * <p>Spelled as the three bytes rather than as a {@code "\uFEFF"}
     * string literal. A BOM character in source is invisible in every editor
     * and diff -- the next person to touch the line cannot see what they are
     * deleting -- and writing bytes straight to the stream also removes any
     * question about how the character would have been encoded.
     */
    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    /**
     * Excel splits on the list separator of the user's locale, which is a
     * semicolon on a French install, not a comma.
     */
    private static final char SEPARATEUR = ';';

    private final AnalytiqueRepository analytiqueRepository;

    /** name -> builder. The single place a new report is registered. */
    private final Map<String, BiFunction<LocalDate, LocalDate, TableauRapport>> rapports;

    public ServiceExport(AnalytiqueRepository analytiqueRepository) {
        this.analytiqueRepository = analytiqueRepository;
        // Phase 6 added "incidents" and phase 9 the last three: one entry and
        // one builder each, and no new CSV or XLSX code -- which was the point
        // of registering reports by name. §4.11 of the cahier des charges lists
        // six exportable reports; these are them.
        this.rapports = Map.of(
                "ponctualite", this::rapportPonctualite,
                "incidents", this::rapportIncidents,
                "retards-par-ligne", this::rapportRetardsParLigne,
                "retards-par-gare", this::rapportRetardsParGare,
                "disponibilite-trains", this::rapportDisponibiliteTrains);
    }

    @PreAuthorize("hasRole('RESPONSABLE_EXPLOITATION')")
    @Transactional(readOnly = true)
    public TableauRapport construire(String nom, LocalDate du, LocalDate au) {
        BiFunction<LocalDate, LocalDate, TableauRapport> constructeur = rapports.get(nom);
        if (constructeur == null) {
            throw new IllegalArgumentException(
                    "Rapport inconnu : " + nom + " (disponible : " + String.join(", ", rapports.keySet()) + ").");
        }
        PlageDates.verifier(du, au);
        return constructeur.apply(du, au);
    }

    /** {@code trino-ponctualite-2026-08-01-2026-08-08.xlsx} */
    public String nomFichier(String nom, LocalDate du, LocalDate au, FormatExport format) {
        return "trino-" + nom + "-" + du + "-" + au + "." + format.extension();
    }

    public void ecrire(TableauRapport tableau, FormatExport format, OutputStream sortie) throws IOException {
        if (format == FormatExport.XLSX) {
            ecrireXlsx(tableau, sortie);
        } else {
            ecrireCsv(tableau, sortie);
        }
    }

    private TableauRapport rapportPonctualite(LocalDate du, LocalDate au) {
        List<PointPonctualiteDTO> points = analytiqueRepository.ponctualite(du, au, Granularite.JOUR);
        List<List<Object>> lignes = new ArrayList<>(points.size());
        for (PointPonctualiteDTO point : points) {
            lignes.add(List.of(
                    point.periode(),
                    point.passages(),
                    point.passagesPonctuels(),
                    point.tauxPonctualite() * 100,
                    point.retardMoyenMin()));
        }
        return new TableauRapport("ponctualite",
                List.of("Date", "Passages mesurés", "Passages à l'heure", "Ponctualité (%)", "Retard moyen (min)"),
                lignes);
    }

    private TableauRapport rapportIncidents(LocalDate du, LocalDate au) {
        List<LigneIncidentsDTO> incidents = analytiqueRepository.incidents(du, au);
        List<List<Object>> lignes = new ArrayList<>(incidents.size());
        for (LigneIncidentsDTO incident : incidents) {
            // Arrays.asList, not List.of: the mean resolution time is null for a
            // bucket where nothing has been resolved, and List.of rejects null
            // with an NPE at export time -- on the one row that most needs to
            // say "nothing closed yet".
            lignes.add(Arrays.asList(
                    incident.type(),
                    incident.gravite(),
                    incident.total(),
                    incident.resolus(),
                    incident.delaiResolutionMoyenH()));
        }
        return new TableauRapport("incidents",
                List.of("Type", "Gravité", "Total", "Résolus", "Délai moyen de résolution (h)"),
                lignes);
    }

    private TableauRapport rapportRetardsParLigne(LocalDate du, LocalDate au) {
        List<RetardParLigneDTO> lignesRetard = analytiqueRepository.retardsParLigne(du, au);
        List<List<Object>> lignes = new ArrayList<>(lignesRetard.size());
        for (RetardParLigneDTO retard : lignesRetard) {
            lignes.add(List.of(
                    retard.ligneNom(),
                    retard.courses(),
                    retard.coursesEnRetard(),
                    // Share of the courses that ran, not of the courses
                    // scheduled: the denominator already excludes ANNULE, and a
                    // cancelled run is not a late one.
                    retard.courses() == 0 ? 0d : 100d * retard.coursesEnRetard() / retard.courses(),
                    retard.retardMoyenMin(),
                    retard.retardMaxMin()));
        }
        return new TableauRapport("retards-par-ligne",
                List.of("Ligne", "Courses", "Courses en retard", "Part en retard (%)",
                        "Retard moyen (min)", "Retard maximum (min)"),
                lignes);
    }

    private TableauRapport rapportRetardsParGare(LocalDate du, LocalDate au) {
        List<RetardParGareDTO> gares = analytiqueRepository.retardsParGare(du, au);
        List<List<Object>> lignes = new ArrayList<>(gares.size());
        for (RetardParGareDTO gare : gares) {
            // Arrays.asList, not List.of: region is nullable on gare, and List.of
            // throws on a null element -- at export time, on whichever station
            // happens to have no region recorded.
            lignes.add(Arrays.asList(
                    gare.gareNom(),
                    gare.region(),
                    gare.passages(),
                    gare.passagesEnRetard(),
                    gare.passages() == 0 ? 0d : 100d * gare.passagesEnRetard() / gare.passages(),
                    gare.retardMoyenMin(),
                    gare.retardMaxMin()));
        }
        return new TableauRapport("retards-par-gare",
                List.of("Gare", "Région", "Passages mesurés", "Passages en retard", "Part en retard (%)",
                        "Retard moyen (min)", "Retard maximum (min)"),
                lignes);
    }

    private TableauRapport rapportDisponibiliteTrains(LocalDate du, LocalDate au) {
        List<DisponibiliteTrainDTO> trains = analytiqueRepository.disponibiliteTrains(du, au);
        List<List<Object>> lignes = new ArrayList<>(trains.size());
        for (DisponibiliteTrainDTO train : trains) {
            // train.nom is nullable in the référentiel, so Arrays.asList again.
            lignes.add(Arrays.asList(
                    train.trainNumero(),
                    train.trainNom(),
                    train.ligneNom(),
                    train.coursesProgrammees(),
                    train.coursesRealisees(),
                    train.coursesAnnulees(),
                    train.tauxDisponibilite() * 100));
        }
        return new TableauRapport("disponibilite-trains",
                List.of("Train", "Nom", "Ligne", "Courses programmées", "Courses réalisées",
                        "Courses annulées", "Disponibilité (%)"),
                lignes);
    }

    private void ecrireCsv(TableauRapport tableau, OutputStream sortie) throws IOException {
        sortie.write(BOM);
        // The writer is not closed: closing it would close the servlet output
        // stream, and the container owns that. Flushed instead.
        Writer writer = new OutputStreamWriter(sortie, StandardCharsets.UTF_8);
        ecrireLigneCsv(writer, tableau.entetes().stream().map(entete -> (Object) entete).toList());
        for (List<Object> ligne : tableau.lignes()) {
            ecrireLigneCsv(writer, ligne);
        }
        writer.flush();
    }

    private void ecrireLigneCsv(Writer writer, List<Object> valeurs) throws IOException {
        StringBuilder ligne = new StringBuilder();
        for (int i = 0; i < valeurs.size(); i++) {
            if (i > 0) {
                ligne.append(SEPARATEUR);
            }
            ligne.append(echapper(formaterCsv(valeurs.get(i))));
        }
        // CRLF: Excel is the target reader, and it is the line ending it emits
        // itself.
        ligne.append("\r\n");
        writer.write(ligne.toString());
    }

    /**
     * Decimals get a comma, not a point. A French-locale Excel parses "82.5"
     * as text and right-aligns nothing; the column then cannot be charted or
     * summed, which defeats exporting it at all.
     */
    private String formaterCsv(Object valeur) {
        if (valeur == null) {
            return "";
        }
        if (valeur instanceof Double nombre) {
            return String.format(Locale.FRANCE, "%.2f", nombre);
        }
        if (valeur instanceof Float nombre) {
            return String.format(Locale.FRANCE, "%.2f", nombre);
        }
        return String.valueOf(valeur);
    }

    private String echapper(String valeur) {
        if (valeur.indexOf(SEPARATEUR) < 0 && valeur.indexOf('"') < 0
                && valeur.indexOf('\n') < 0 && valeur.indexOf('\r') < 0) {
            return valeur;
        }
        return '"' + valeur.replace("\"", "\"\"") + '"';
    }

    /**
     * SXSSF rather than XSSF: it spills rows to disk instead of holding the
     * whole sheet in memory. A year of daily rows would fit either way, but the
     * streaming variant is what makes an unbounded report safe to add later.
     */
    private void ecrireXlsx(TableauRapport tableau, OutputStream sortie) throws IOException {
        SXSSFWorkbook classeur = new SXSSFWorkbook(100);
        try {
            Sheet feuille = classeur.createSheet(tableau.nom());

            Font graisse = classeur.createFont();
            graisse.setBold(true);
            CellStyle styleEntete = classeur.createCellStyle();
            styleEntete.setFont(graisse);

            CellStyle styleDate = classeur.createCellStyle();
            styleDate.setDataFormat(classeur.createDataFormat().getFormat("yyyy-mm-dd"));

            Row entete = feuille.createRow(0);
            for (int colonne = 0; colonne < tableau.entetes().size(); colonne++) {
                Cell cellule = entete.createCell(colonne);
                cellule.setCellValue(tableau.entetes().get(colonne));
                cellule.setCellStyle(styleEntete);
            }

            int numeroLigne = 1;
            for (List<Object> valeurs : tableau.lignes()) {
                Row ligne = feuille.createRow(numeroLigne++);
                for (int colonne = 0; colonne < valeurs.size(); colonne++) {
                    remplir(ligne.createCell(colonne), valeurs.get(colonne), styleDate);
                }
            }
            classeur.write(sortie);
            sortie.flush();
        } finally {
            // close() then dispose(), in that order: dispose() deletes the
            // temporary files backing the sheets and renders the workbook
            // unusable, so closing afterwards would be operating on something
            // already torn down. Both are needed -- close() releases the
            // package, dispose() is what stops SXSSF leaking spill files into
            // the system temp directory for the life of the process.
            classeur.close();
            classeur.dispose();
        }
    }

    /** Numbers stay numbers: a spreadsheet nobody can sum is a screenshot. */
    private void remplir(Cell cellule, Object valeur, CellStyle styleDate) {
        switch (valeur) {
            case null -> cellule.setBlank();
            case LocalDate date -> {
                cellule.setCellValue(date);
                cellule.setCellStyle(styleDate);
            }
            case Number nombre -> cellule.setCellValue(nombre.doubleValue());
            default -> cellule.setCellValue(String.valueOf(valeur));
        }
    }
}
