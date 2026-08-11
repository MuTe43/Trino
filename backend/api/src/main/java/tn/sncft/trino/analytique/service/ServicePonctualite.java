package tn.sncft.trino.analytique.service;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.sncft.trino.analytique.dto.BucketRetardDTO;
import tn.sncft.trino.analytique.dto.CaseHeatmapDTO;
import tn.sncft.trino.analytique.dto.Granularite;
import tn.sncft.trino.analytique.dto.PointPonctualiteDTO;
import tn.sncft.trino.analytique.repository.AnalytiqueRepository;
import tn.sncft.trino.commun.PlageDates;
import tn.sncft.trino.circulation.domaine.ClasseRetard;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Punctuality over a range, and the gare x hour grid.
 *
 * <p>The range is what gets presented. Two runs of the same simulated day gave
 * 28.8 % and 23.3 % of courses 5+ minutes late in phase 2 -- about ±5 points of
 * noise on a stochastic perturbation model -- so a headline built on one day
 * moves every time the demo is reset.
 */
@Service
public class ServicePonctualite {

    private final AnalytiqueRepository analytiqueRepository;

    public ServicePonctualite(AnalytiqueRepository analytiqueRepository) {
        this.analytiqueRepository = analytiqueRepository;
    }

    @PreAuthorize("hasRole('RESPONSABLE_EXPLOITATION')")
    @Transactional(readOnly = true)
    public List<PointPonctualiteDTO> ponctualite(LocalDate du, LocalDate au, Granularite granularite) {
        verifierPlage(du, au);
        return analytiqueRepository.ponctualite(du, au, granularite);
    }

    @PreAuthorize("hasRole('RESPONSABLE_EXPLOITATION')")
    @Transactional(readOnly = true)
    public List<CaseHeatmapDTO> heatmap(LocalDate du, LocalDate au) {
        verifierPlage(du, au);
        return analytiqueRepository.heatmap(du, au);
    }

    /**
     * The delay histogram, bucketed here rather than in SQL so
     * {@link ClasseRetard#de(int)} stays the only definition of the boundaries.
     *
     * <p>Every bucket is returned, including empty ones: a histogram that drops
     * its zero bars silently rescales itself, and "no course was over an hour
     * late" is exactly the kind of thing a reader should be able to see.
     */
    @PreAuthorize("hasRole('RESPONSABLE_EXPLOITATION')")
    @Transactional(readOnly = true)
    public List<BucketRetardDTO> distributionRetards(LocalDate du, LocalDate au) {
        verifierPlage(du, au);

        Map<ClasseRetard, Long> parClasse = new EnumMap<>(ClasseRetard.class);
        for (ClasseRetard classe : ClasseRetard.values()) {
            parClasse.put(classe, 0L);
        }
        for (AnalytiqueRepository.CompteRetard compte : analytiqueRepository.coursesParRetard(du, au)) {
            parClasse.merge(ClasseRetard.de(compte.retardMin()), compte.courses(), Long::sum);
        }
        return parClasse.entrySet().stream()
                .map(entree -> new BucketRetardDTO(entree.getKey(), entree.getValue()))
                .toList();
    }

    /**
     * IllegalArgumentException is what ApiExceptionHandler renders as a 400
     * VALIDATION_ECHOUEE envelope, so these read the same as any other bad
     * request rather than as a 500.
     */
    private void verifierPlage(LocalDate du, LocalDate au) {
        PlageDates.verifier(du, au);
    }
}
