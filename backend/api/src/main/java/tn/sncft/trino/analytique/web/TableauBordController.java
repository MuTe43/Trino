package tn.sncft.trino.analytique.web;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tn.sncft.trino.analytique.dto.BucketRetardDTO;
import tn.sncft.trino.analytique.dto.CaseHeatmapDTO;
import tn.sncft.trino.analytique.dto.KpiJourDTO;
import tn.sncft.trino.analytique.dto.RetardParLigneDTO;
import tn.sncft.trino.analytique.service.ServiceKpi;
import tn.sncft.trino.analytique.service.ServicePonctualite;

import java.time.LocalDate;
import java.util.List;

/**
 * The operations dashboard. RESPONSABLE_EXPLOITATION only, enforced both by a
 * URL rule in {@code ConfigurationSecurite} and by {@code @PreAuthorize} on the
 * services (invariant 9).
 *
 * <p>No logic here (invariant 7): binding and delegation only.
 */
@RestController
@RequestMapping("/api/v1/tableau-bord")
public class TableauBordController {

    private final ServiceKpi serviceKpi;
    private final ServicePonctualite servicePonctualite;

    public TableauBordController(ServiceKpi serviceKpi, ServicePonctualite servicePonctualite) {
        this.serviceKpi = serviceKpi;
        this.servicePonctualite = servicePonctualite;
    }

    @GetMapping("/kpi")
    public KpiJourDTO kpi(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return serviceKpi.kpiDuJour(date);
    }

    @GetMapping("/retards-par-ligne")
    public List<RetardParLigneDTO> retardsParLigne(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return serviceKpi.retardsParLigne(date);
    }

    @GetMapping("/heatmap")
    public List<CaseHeatmapDTO> heatmap(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate du,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate au) {
        return servicePonctualite.heatmap(du, au);
    }

    /**
     * Feeds the delay histogram. Not in the phase spec's query list, but its
     * Charts section asks for a histogram of delay buckets and none of the
     * other endpoints carries that distribution.
     */
    @GetMapping("/distribution-retards")
    public List<BucketRetardDTO> distributionRetards(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate du,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate au) {
        return servicePonctualite.distributionRetards(du, au);
    }
}
