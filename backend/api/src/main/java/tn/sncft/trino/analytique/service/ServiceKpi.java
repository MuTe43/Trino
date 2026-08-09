package tn.sncft.trino.analytique.service;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.sncft.trino.analytique.dto.KpiJourDTO;
import tn.sncft.trino.analytique.dto.RetardParLigneDTO;
import tn.sncft.trino.analytique.repository.AnalytiqueRepository;

import java.time.LocalDate;
import java.util.List;

/**
 * The operational figures behind {@code /tableau-bord}.
 *
 * <p>{@code @PreAuthorize} here and a URL rule in {@code
 * ConfigurationSecurite}: invariant 9. These are reads rather than writes, so
 * the {@code @Valid}-before-authorization ordering trap does not apply, but the
 * URL rule is still what produces a clean 403 in the filter chain before any
 * argument binding runs -- a bad {@code date=} on an endpoint the caller may not
 * touch should answer 403, not 400.
 *
 * <p>{@code RESPONSABLE_EXPLOITATION} only. ADMINISTRATEUR is deliberately not
 * included: it administers the référentiel, accounts and the connection log,
 * and operational dashboards are a different duty.
 */
@Service
public class ServiceKpi {

    private final AnalytiqueRepository analytiqueRepository;

    public ServiceKpi(AnalytiqueRepository analytiqueRepository) {
        this.analytiqueRepository = analytiqueRepository;
    }

    @PreAuthorize("hasRole('RESPONSABLE_EXPLOITATION')")
    @Transactional(readOnly = true)
    public KpiJourDTO kpiDuJour(LocalDate date) {
        AnalytiqueRepository.CompteursJour compteurs = analytiqueRepository.compteursDuJour(date);
        AnalytiqueRepository.CompteursPonctualite ponctualite = analytiqueRepository.ponctualiteDuJour(date);

        return new KpiJourDTO(
                date,
                compteurs.trains(),
                compteurs.retards(),
                arrondir(compteurs.retardMoyenMin()),
                arrondir(ponctualite.taux(), 4),
                ponctualite.mesures(),
                // phase 6 -- the incident table does not exist yet. Deliberately
                // a literal zero rather than an empty table queried for nothing:
                // creating the table early would take schema ownership from the
                // phase that needs it, and both answers are the same 0.
                0L,
                0L,
                compteurs.annules(),
                compteurs.voyageursImpactes());
    }

    @PreAuthorize("hasRole('RESPONSABLE_EXPLOITATION')")
    @Transactional(readOnly = true)
    public List<RetardParLigneDTO> retardsParLigne(LocalDate date) {
        return analytiqueRepository.retardsParLigne(date);
    }

    private static double arrondir(double valeur) {
        return arrondir(valeur, 1);
    }

    /** Keeps the JSON free of 28.799999999999997. */
    private static double arrondir(double valeur, int decimales) {
        double facteur = Math.pow(10, decimales);
        return Math.round(valeur * facteur) / facteur;
    }
}
