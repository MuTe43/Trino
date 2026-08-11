package tn.sncft.trino.analytique.service;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.sncft.trino.analytique.dto.LigneIncidentsDTO;
import tn.sncft.trino.analytique.repository.AnalytiqueRepository;
import tn.sncft.trino.commun.PlageDates;

import java.time.LocalDate;
import java.util.List;

/**
 * {@code GET /rapports/incidents}. Deferred from phase 5 (the table did not
 * exist yet), delivered here.
 *
 * <p>{@code RESPONSABLE_EXPLOITATION} only, like every other report -- and
 * paired with the URL rule on {@code /api/v1/rapports/**} per invariant 9.
 */
@Service
public class ServiceRapportIncidents {

    private final AnalytiqueRepository analytiqueRepository;

    public ServiceRapportIncidents(AnalytiqueRepository analytiqueRepository) {
        this.analytiqueRepository = analytiqueRepository;
    }

    @PreAuthorize("hasRole('RESPONSABLE_EXPLOITATION')")
    @Transactional(readOnly = true)
    public List<LigneIncidentsDTO> incidents(LocalDate du, LocalDate au) {
        PlageDates.verifier(du, au);
        return analytiqueRepository.incidents(du, au);
    }
}
