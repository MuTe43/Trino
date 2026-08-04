package tn.sncft.trino.circulation.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.sncft.trino.circulation.domaine.Course;
import tn.sncft.trino.circulation.domaine.PassageGare;
import tn.sncft.trino.circulation.domaine.PositionCourse;
import tn.sncft.trino.circulation.domaine.StatutCourse;
import tn.sncft.trino.circulation.dto.CourseDuJourDTO;
import tn.sncft.trino.circulation.dto.LotPingsDTO;
import tn.sncft.trino.circulation.dto.PingDTO;
import tn.sncft.trino.circulation.dto.ResultatIngestionDTO;
import tn.sncft.trino.circulation.geo.FabriqueGeometrie;
import tn.sncft.trino.circulation.geo.GeometrieLigne;
import tn.sncft.trino.circulation.repo.CourseRepository;
import tn.sncft.trino.circulation.repo.PassageGareRepository;
import tn.sncft.trino.circulation.repo.PositionCourseRepository;
import tn.sncft.trino.referentiel.domaine.Gare;
import tn.sncft.trino.referentiel.domaine.Ligne;
import tn.sncft.trino.referentiel.domaine.Train;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The seam between Trino and whatever is producing positions. The simulator is
 * one implementation; real AVL hardware would be another, and nothing else in
 * the system knows which one is connected.
 *
 * <p>Scope is deliberately narrow: this writes {@code position_course} and
 * moves {@code course.avancement_km} / {@code derniere_position_at}. It does
 * NOT compute delays, revise estimates or change status -- that is the delay
 * engine in phase 3. Keeping that seam clean is the point; an ingestion path
 * that also decides a train is late is one that cannot be swapped out.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private static final ZoneId ZONE_RESEAU = ZoneId.of("Africa/Tunis");

    /** A course in one of these states no longer accepts positions. */
    private static final Set<StatutCourse> STATUTS_CLOS =
            EnumSet.of(StatutCourse.ANNULE, StatutCourse.TERMINUS_ATTEINT);

    private final CourseRepository courseRepository;
    private final PassageGareRepository passageGareRepository;
    private final PositionCourseRepository positionCourseRepository;
    private final FabriqueGeometrie fabriqueGeometrie;

    public IngestionService(CourseRepository courseRepository,
                            PassageGareRepository passageGareRepository,
                            PositionCourseRepository positionCourseRepository,
                            FabriqueGeometrie fabriqueGeometrie) {
        this.courseRepository = courseRepository;
        this.passageGareRepository = passageGareRepository;
        this.positionCourseRepository = positionCourseRepository;
        this.fabriqueGeometrie = fabriqueGeometrie;
    }

    /** The runs of the current service day that can still receive positions. */
    @PreAuthorize("hasRole('INGESTION')")
    @Transactional(readOnly = true)
    public List<CourseDuJourDTO> coursesDuJour() {
        LocalDate aujourdhui = LocalDate.now(ZONE_RESEAU);
        List<Course> courses = courseRepository.findDuJourSaufStatuts(aujourdhui, STATUTS_CLOS);
        if (courses.isEmpty()) {
            return List.of();
        }

        Map<Long, List<PassageGare>> passagesParCourse = chargerPassages(
                courses.stream().map(Course::getId).toList());

        // One ligne with an unusable trace must not take the whole feed down.
        // Symmetric with ingerer(): a broken geometry costs you that course,
        // not every course of the day. A ligne can lose its trace through an
        // ordinary PUT /lignes/{id} that omits it.
        List<CourseDuJourDTO> resultat = new ArrayList<>(courses.size());
        for (Course course : courses) {
            try {
                resultat.add(versDTO(course, passagesParCourse.getOrDefault(course.getId(), List.of())));
            } catch (RuntimeException e) {
                log.warn("Course {} exclue du flux : {}", course.getId(), e.getMessage());
            }
        }
        return resultat;
    }

    /**
     * Records a batch of positions. One {@code saveAll} for the whole batch,
     * not one insert per ping.
     */
    // Invariant 8: the URL rule in ConfigurationSecurite is what produces the
    // correct 401/403 (it runs before @Valid), and this is the defence in
    // depth for service-to-service calls. Both, never one or the other.
    @PreAuthorize("hasRole('INGESTION')")
    @Transactional
    public ResultatIngestionDTO ingerer(LotPingsDTO lot) {
        List<PingDTO> pings = lot.pings();
        if (pings.isEmpty()) {
            return new ResultatIngestionDTO(0, 0);
        }

        Map<Long, List<PingDTO>> parCourse = pings.stream()
                .collect(Collectors.groupingBy(PingDTO::courseId));

        Map<Long, Course> courses = courseRepository.findAvecLigneEtTrain(parCourse.keySet()).stream()
                .collect(Collectors.toMap(Course::getId, Function.identity()));
        Map<Long, List<PassageGare>> passagesParCourse = chargerPassages(courses.keySet());

        List<PositionCourse> aEnregistrer = new ArrayList<>();
        List<Course> aMettreAJour = new ArrayList<>();
        int rejetes = 0;

        for (Map.Entry<Long, List<PingDTO>> entree : parCourse.entrySet()) {
            Course course = courses.get(entree.getKey());
            if (course == null || STATUTS_CLOS.contains(course.getStatut())) {
                rejetes += entree.getValue().size();
                continue;
            }

            List<PassageGare> passages = passagesParCourse.getOrDefault(course.getId(), List.of());
            if (passages.size() < 2) {
                log.warn("Course {} sans desserte exploitable, {} ping(s) rejeté(s).",
                        course.getId(), entree.getValue().size());
                rejetes += entree.getValue().size();
                continue;
            }

            GeometrieLigne geometrie;
            try {
                geometrie = fabriqueGeometrie.pour(course, passages);
            } catch (RuntimeException e) {
                // One unusable ligne must not fail the batch: the producer
                // sends every course it is tracking in a single request, and
                // dropping all of them because one line's geometry is broken
                // would take the whole map down instead of one train.
                log.warn("Course {} : géométrie inexploitable ({}), {} ping(s) rejeté(s).",
                        course.getId(), e.getMessage(), entree.getValue().size());
                rejetes += entree.getValue().size();
                continue;
            }

            List<PingDTO> ordonnes = entree.getValue().stream()
                    .sorted(Comparator.comparing(PingDTO::horodatage))
                    .toList();

            BigDecimal dernierAvancement = null;
            for (PingDTO ping : ordonnes) {
                double avancement = geometrie.projeter(
                        ping.latitude().doubleValue(), ping.longitude().doubleValue());
                dernierAvancement = BigDecimal.valueOf(avancement).setScale(2, RoundingMode.HALF_UP);
                aEnregistrer.add(versPosition(course, ping, dernierAvancement, passages));
            }

            // The batch may arrive out of order; the course carries the latest
            // fix, so only the last ping by timestamp wins.
            PingDTO dernier = ordonnes.get(ordonnes.size() - 1);
            if (course.getDernierePositionAt() == null
                    || dernier.horodatage().isAfter(course.getDernierePositionAt())) {
                course.setAvancementKm(dernierAvancement);
                course.setDernierePositionAt(dernier.horodatage());
                aMettreAJour.add(course);
            }
        }

        positionCourseRepository.saveAll(aEnregistrer);
        courseRepository.saveAll(aMettreAJour);
        return new ResultatIngestionDTO(aEnregistrer.size(), rejetes);
    }

    private PositionCourse versPosition(Course course, PingDTO ping, BigDecimal avancementKm,
                                        List<PassageGare> passages) {
        PositionCourse position = new PositionCourse();
        position.setCourse(course);
        position.setHorodatage(ping.horodatage());
        position.setLatitude(ping.latitude());
        position.setLongitude(ping.longitude());
        position.setVitesseKmh(ping.vitesseKmh());
        position.setAvancementKm(avancementKm);
        position.setGarePrecedente(garePrecedente(passages, avancementKm));
        position.setGareSuivante(gareSuivante(passages, avancementKm));
        // eta_suivante stays null: ETA is speed-based and belongs to the delay
        // engine (decision 6). Ingestion records, it does not predict.
        return position;
    }

    private Gare garePrecedente(List<PassageGare> passages, BigDecimal avancementKm) {
        Gare precedente = null;
        for (PassageGare passage : passages) {
            if (passage.getPkKm().compareTo(avancementKm) <= 0) {
                precedente = passage.getGare();
            }
        }
        return precedente;
    }

    private Gare gareSuivante(List<PassageGare> passages, BigDecimal avancementKm) {
        for (PassageGare passage : passages) {
            if (passage.getPkKm().compareTo(avancementKm) > 0) {
                return passage.getGare();
            }
        }
        return null;
    }

    private Map<Long, List<PassageGare>> chargerPassages(Collection<Long> courseIds) {
        if (courseIds.isEmpty()) {
            return Map.of();
        }
        return passageGareRepository.findByCourseIds(courseIds).stream()
                .collect(Collectors.groupingBy(passage -> passage.getCourse().getId(),
                        HashMap::new, Collectors.toList()));
    }

    private CourseDuJourDTO versDTO(Course course, List<PassageGare> passages) {
        Ligne ligne = course.getLigne();
        Train train = course.getTrain();
        return new CourseDuJourDTO(
                course.getId(),
                course.getSens(),
                course.getDepartTheorique(),
                course.getArriveeTheorique(),
                course.getAvancementKm(),
                new CourseDuJourDTO.TrainCourseDTO(
                        train.getId(), train.getNumero(), train.getNom(), train.getVitesseMaxKmh()),
                new CourseDuJourDTO.LigneCourseDTO(
                        ligne.getId(), ligne.getCode(), ligne.getNom(),
                        ligne.getDistanceKm(), ligne.getVitesseMaxKmh()),
                fabriqueGeometrie.trace(ligne),
                passages.stream().map(this::versArretDTO).toList());
    }

    private CourseDuJourDTO.ArretCourseDTO versArretDTO(PassageGare passage) {
        Gare gare = passage.getGare();
        return new CourseDuJourDTO.ArretCourseDTO(
                gare.getId(),
                gare.getCode(),
                gare.getNom(),
                passage.getOrdre(),
                passage.getPkKm(),
                gare.getLatitude(),
                gare.getLongitude(),
                passage.getArriveeTheorique(),
                passage.getDepartTheorique());
    }
}
