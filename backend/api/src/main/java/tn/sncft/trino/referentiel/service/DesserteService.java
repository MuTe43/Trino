package tn.sncft.trino.referentiel.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.sncft.trino.referentiel.domaine.Desserte;
import tn.sncft.trino.referentiel.repo.DesserteRepository;

import java.util.List;

/**
 * Read access to the theoretical stop pattern, for modules outside
 * référentiel. Circulation materialises the daily timetable from this and must
 * not reach into {@link DesserteRepository} itself: modules talk through
 * service interfaces (decision 1).
 */
@Service
public class DesserteService {

    private final DesserteRepository desserteRepository;

    public DesserteService(DesserteRepository desserteRepository) {
        this.desserteRepository = desserteRepository;
    }

    /** The stops of a ligne, ascending by ordre, with their gare loaded. */
    @Transactional(readOnly = true)
    public List<Desserte> parLigne(Long ligneId) {
        return desserteRepository.findByLigneIdOrderByOrdreAsc(ligneId);
    }
}
