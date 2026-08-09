package tn.sncft.trino.analytique.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import tn.sncft.trino.analytique.dto.FormatExport;
import tn.sncft.trino.analytique.dto.PointPonctualiteDTO;
import tn.sncft.trino.analytique.dto.TableauRapport;
import tn.sncft.trino.analytique.repository.AnalytiqueRepository;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The export details that decide whether the file opens correctly when a
 * supervisor double-clicks it, which is the only way it will ever be opened.
 */
class ServiceExportTest {

    private static final LocalDate DU = LocalDate.of(2026, 8, 1);
    private static final LocalDate AU = LocalDate.of(2026, 8, 8);

    private final AnalytiqueRepository depot = mock(AnalytiqueRepository.class);
    private final ServiceExport service = new ServiceExport(depot);

    private void stubPonctualite() {
        when(depot.ponctualite(any(), any(), any())).thenReturn(List.of(
                new PointPonctualiteDTO(LocalDate.of(2026, 8, 1), 400, 330, 0.825, 3.5),
                new PointPonctualiteDTO(LocalDate.of(2026, 8, 2), 410, 300, 0.7317, 4.25)));
    }

    private byte[] exporter(FormatExport format) throws Exception {
        stubPonctualite();
        TableauRapport tableau = service.construire("ponctualite", DU, AU);
        ByteArrayOutputStream sortie = new ByteArrayOutputStream();
        service.ecrire(tableau, format, sortie);
        return sortie.toByteArray();
    }

    @Test
    @DisplayName("le CSV commence par un BOM UTF-8")
    void leCsvCommenceParUnBom() throws Exception {
        byte[] octets = exporter(FormatExport.CSV);

        // Without these three bytes Excel on a French Windows locale reads the
        // file as Windows-1252 and every accent arrives mojibaked.
        assertEquals((byte) 0xEF, octets[0]);
        assertEquals((byte) 0xBB, octets[1]);
        assertEquals((byte) 0xBF, octets[2]);
    }

    @Test
    @DisplayName("le CSV utilise le point-virgule et la virgule décimale")
    void leCsvUtiliseLesConventionsFrancaises() throws Exception {
        String contenu = new String(exporter(FormatExport.CSV), StandardCharsets.UTF_8);
        List<String> lignes = contenu.lines().toList();

        assertTrue(lignes.get(0).contains("Date;Passages mesurés"), lignes.get(0));
        // 0.825 -> 82,50 : a French Excel parses "82.50" as text, and a text
        // column cannot be summed or charted.
        assertTrue(lignes.get(1).contains(";82,50;"), lignes.get(1));
        assertTrue(lignes.get(1).startsWith("2026-08-01;400;330;"), lignes.get(1));
        assertTrue(contenu.contains("\r\n"), "l'export doit utiliser des fins de ligne CRLF");
    }

    @Test
    @DisplayName("le XLSX est un classeur lisible dont les nombres sont des nombres")
    void leXlsxEstUnClasseurLisible() throws Exception {
        byte[] octets = exporter(FormatExport.XLSX);

        // PK\x03\x04 -- an OOXML file is a zip, which is what `file` reports on.
        assertEquals('P', octets[0]);
        assertEquals('K', octets[1]);

        try (Workbook classeur = new XSSFWorkbook(new ByteArrayInputStream(octets))) {
            var feuille = classeur.getSheet("ponctualite");
            assertEquals("Date", feuille.getRow(0).getCell(0).getStringCellValue());
            // Numeric, not text: the column has to be summable in the sheet.
            assertEquals(400, feuille.getRow(1).getCell(1).getNumericCellValue(), 0.001);
            assertEquals(82.5, feuille.getRow(1).getCell(3).getNumericCellValue(), 0.001);
            assertEquals(LocalDate.of(2026, 8, 1),
                    feuille.getRow(1).getCell(0).getLocalDateTimeCellValue().toLocalDate());
        }
    }

    @Test
    @DisplayName("le nom de fichier porte le rapport et les bornes")
    void leNomDeFichierEstExplicite() {
        assertEquals("trino-ponctualite-2026-08-01-2026-08-08.xlsx",
                service.nomFichier("ponctualite", DU, AU, FormatExport.XLSX));
        assertEquals("trino-ponctualite-2026-08-01-2026-08-08.csv",
                service.nomFichier("ponctualite", DU, AU, FormatExport.CSV));
    }

    @Test
    @DisplayName("un rapport inconnu est une requête invalide, pas une 500")
    void unRapportInconnuEstRefuse() {
        // IllegalArgumentException is what ApiExceptionHandler renders as a 400
        // VALIDATION_ECHOUEE envelope.
        assertThrows(IllegalArgumentException.class, () -> service.construire("inexistant", DU, AU));
    }

    @Test
    @DisplayName("une plage à l'envers est refusée")
    void unePlageInverseeEstRefusee() {
        assertThrows(IllegalArgumentException.class, () -> service.construire("ponctualite", AU, DU));
    }

    @Test
    @DisplayName("un format inconnu est refusé, et l'absence de format donne du CSV")
    void leFormatEstValide() {
        assertEquals(FormatExport.CSV, FormatExport.depuis(null));
        assertEquals(FormatExport.XLSX, FormatExport.depuis("xlsx"));
        assertEquals(FormatExport.XLSX, FormatExport.depuis("XLSX"));
        assertThrows(IllegalArgumentException.class, () -> FormatExport.depuis("pdf"));
    }
}
