package tn.sncft.trino.circulation.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The only implementation this deployment needs. One bounded window of fixes
 * per running course, which at ~80 courses a day is a few kilobytes.
 */
@Component
public class EtatCirculationEnMemoire implements EtatCirculationStore {

    /**
     * Seven fixes, because the chainage speed is measured over k = 6 intervals
     * and that needs the endpoints of the window plus everything between.
     */
    static final int PROFONDEUR = 7;

    private final Map<Long, List<FixPosition>> etats = new ConcurrentHashMap<>();

    @Override
    public EtatCirculation mettreAJour(long courseId, FixPosition fix) {
        // The whole update runs inside compute() so concurrent ingestion
        // batches for the same course cannot interleave a read and a write and
        // lose a fix.
        List<FixPosition> fenetre = etats.compute(courseId, (id, actuel) -> {
            List<FixPosition> fusion = actuel == null ? new ArrayList<>() : new ArrayList<>(actuel);
            fusion.add(fix);
            // A batch can arrive out of order. Sorting here is what guarantees
            // the speed window spans a positive time interval.
            fusion.sort(Comparator.comparing(FixPosition::horodatage));
            if (fusion.size() > PROFONDEUR) {
                fusion = new ArrayList<>(fusion.subList(fusion.size() - PROFONDEUR, fusion.size()));
            }
            return List.copyOf(fusion);
        });
        return new EtatCirculation(courseId, fenetre.get(fenetre.size() - 1), fenetre);
    }

    @Override
    public Optional<EtatCirculation> lire(long courseId) {
        List<FixPosition> fenetre = etats.get(courseId);
        if (fenetre == null || fenetre.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new EtatCirculation(courseId, fenetre.get(fenetre.size() - 1), fenetre));
    }

    @Override
    public void oublier(long courseId) {
        etats.remove(courseId);
    }
}
