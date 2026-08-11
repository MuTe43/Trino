package tn.sncft.trino.analytique.web;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tn.sncft.trino.analytique.dto.FormatExport;
import tn.sncft.trino.analytique.dto.Granularite;
import tn.sncft.trino.analytique.dto.LigneIncidentsDTO;
import tn.sncft.trino.analytique.dto.PointPonctualiteDTO;
import tn.sncft.trino.analytique.dto.TableauRapport;
import tn.sncft.trino.analytique.service.ServiceExport;
import tn.sncft.trino.analytique.service.ServicePonctualite;
import tn.sncft.trino.analytique.service.ServiceRapportIncidents;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

/**
 * Reports, as JSON or as a downloadable file.
 *
 * <p>No logic here (invariant 7). The one thing this class decides is HTTP:
 * the content type, the attachment header and the file name.
 */
@RestController
@RequestMapping("/api/v1/rapports")
public class RapportController {

    private final ServicePonctualite servicePonctualite;
    private final ServiceRapportIncidents serviceRapportIncidents;
    private final ServiceExport serviceExport;

    public RapportController(ServicePonctualite servicePonctualite,
                             ServiceRapportIncidents serviceRapportIncidents,
                             ServiceExport serviceExport) {
        this.servicePonctualite = servicePonctualite;
        this.serviceRapportIncidents = serviceRapportIncidents;
        this.serviceExport = serviceExport;
    }

    @GetMapping("/ponctualite")
    public List<PointPonctualiteDTO> ponctualite(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate du,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate au,
            @RequestParam(defaultValue = "JOUR") Granularite granularite) {
        return servicePonctualite.ponctualite(du, au, granularite);
    }

    @GetMapping("/incidents")
    public List<LigneIncidentsDTO> incidents(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate du,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate au) {
        return serviceRapportIncidents.incidents(du, au);
    }

    /**
     * The report as a file, written straight to the response.
     *
     * <p>Deliberately NOT a {@link org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody}.
     * That turns the request into an async one, and the container then re-runs
     * the filter chain on the async dispatch. Spring Security's
     * {@code AuthorizationFilter} filters every dispatcher type, but
     * {@code FiltreJwt} extends {@code OncePerRequestFilter}, whose
     * {@code shouldNotFilterAsyncDispatch()} is {@code true} by default -- so on
     * the re-dispatch nobody re-authenticates, the role rule on
     * {@code /api/v1/rapports/**} denies an anonymous request, and the denial
     * arrives after {@code text/csv} has already been committed. The file still
     * downloads; the server logs three ERROR stack traces per export, which is
     * exactly the noise phase 4 spent its time eliminating.
     *
     * <p>Writing synchronously keeps it a stream to the client -- bytes go out
     * through the response's own output stream, nothing is buffered into a
     * {@code byte[]} first -- without ever starting an async context. A report
     * of a year of daily rows is a few hundred lines, so there is nothing here
     * that needs to outlive the request thread.
     *
     * <p>The guarded call runs before a single byte is written, so a caller who
     * may not read this report still gets a clean 403 with an intact envelope.
     */
    @GetMapping("/{nom}/export")
    public void export(
            @PathVariable String nom,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate du,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate au,
            @RequestParam(required = false) String format,
            HttpServletResponse reponse) throws IOException {

        FormatExport formatExport = FormatExport.depuis(format);
        TableauRapport tableau = serviceExport.construire(nom, du, au);
        String nomFichier = serviceExport.nomFichier(nom, du, au, formatExport);

        reponse.setStatus(HttpServletResponse.SC_OK);
        reponse.setContentType(formatExport.typeContenu());
        reponse.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + nomFichier + "\"");

        serviceExport.ecrire(tableau, formatExport, reponse.getOutputStream());
        reponse.flushBuffer();
    }
}
