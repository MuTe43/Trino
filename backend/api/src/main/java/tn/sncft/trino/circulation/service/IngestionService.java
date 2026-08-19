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
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The seam between Trino and whatever is producing positions. The simulator is
 * one implementation; real AVL hardware would be another, and nothing else in
 * the system knows which one is connected.
 *
 * <p>This records the ping and then drives the delay engine over it, in the
 * order set out in phase-3.md: hot state, delay and propagation, state machine,
 * ETA, publish. The arithmetic itself lives in {@link MoteurRetard},
 * {@link MachineEtatCourse} and {@link CalculateurEta} rather than here, so the
 * seam stays swappable -- what a producer sends is still just positions.
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
    private final EtatCirculationStore etatStore;
    private final MoteurRetard moteurRetard;
    private final MachineEtatCourse machineEtatCourse;
    private final CalculateurEta calculateurEta;
    private final DiffuseurCirculation diffuseur;
    private final HorlogeCirculation horloge;

    public IngestionService(CourseRepository courseRepository,
                            PassageGareRepository passageGareRepository,
                            PositionCourseRepository positionCourseRepository,
                            FabriqueGeometrie fabriqueGeometrie,
                            EtatCirculationStore etatStore,
                            MoteurRetard moteurRetard,
                            MachineEtatCourse machineEtatCourse,
                            CalculateurEta calculateurEta,
                            DiffuseurCirculation diffuseur,
                            HorlogeCirculation horloge) {
        this.courseRepository = courseRepository;
        this.passageGareRepository = passageGareRepository;
        this.positionCourseRepository = positionCourseRepository;
        this.fabriqueGeometrie = fabriqueGeometrie;
        this.etatStore = etatStore;
        this.moteurRetard = moteurRetard;
        this.machineEtatCourse = machineEtatCourse;
        this.calculateurEta = calculateurEta;
        this.diffuseur = diffuseur;
        this.horloge = horloge;
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
    // Invariant 9: the URL rule in ConfigurationSecurite is what produces the
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

            // The engine runs per ping, not once per batch: a stop crossed
            // between two pings must be stamped with the timestamp of the ping
            // that crossed it, or every real time in a batch collapses onto the
            // last one and the measured delays drift.
            BigDecimal dernierAvancement = null;
            EtatCirculation etat = null;
            OffsetDateTime dernierEta = null;
            List<PassageGare> revises = new ArrayList<>();
            boolean franchissement = false;

            for (PingDTO ping : ordonnes) {
                double avancement = geometrie.projeter(
                        ping.latitude().doubleValue(), ping.longitude().doubleValue());
                dernierAvancement = BigDecimal.valueOf(avancement).setScale(2, RoundingMode.HALF_UP);

                horloge.observer(ping.horodatage());

                // 1. hot state
                etat = etatStore.mettreAJour(course.getId(), new FixPosition(
                        ping.horodatage(), ping.latitude(), ping.longitude(),
                        ping.vitesseKmh(), dernierAvancement));

                // 2. real times, delay, forward propagation
                MoteurRetard.ResultatRetard resultat =
                        moteurRetard.traiter(course, passages, ping.horodatage(), dernierAvancement);
                franchissement |= resultat.franchissement();
                fusionnerRevises(revises, resultat.revises());

                // 4. ETA, from a chainage speed -- never from ping.vitesseKmh
                dernierEta = calculateurEta.pour(course, passages, etat);
                aEnregistrer.add(versPosition(course, ping, dernierAvancement, passages, dernierEta));
            }

            // The batch may arrive out of order; the course carries the latest
            // fix, so only the last ping by timestamp wins.
            PingDTO dernier = ordonnes.get(ordonnes.size() - 1);
            if (course.getDernierePositionAt() == null
                    || dernier.horodatage().isAfter(course.getDernierePositionAt())) {
                course.setAvancementKm(dernierAvancement);
                course.setDernierePositionAt(dernier.horodatage());
            }
            aMettreAJour.add(course);

            // 3. the state machine, once the course carries its latest ping --
            // it reads derniere_position_at to decide whether the feed is live.
            Optional<StatutCourse> change =
                    machineEtatCourse.evaluer(course, passages, horloge.maintenant());

            // 5. publish, once per course rather than once per ping: a batch of
            // six fixes is one movement as far as a subscriber is concerned.
            diffuseur.position(course, passages, etat.dernier(), dernierEta);
            if (franchissement || !revises.isEmpty()) {
                diffuseur.retard(course, passages, revises);
            }
            change.ifPresent(statut -> {
                diffuseur.statut(course, passages, statut);
                // Every terminal status, not only TERMINUS_ATTEINT -- see the
                // same call in DetecteurSilence.
                if (MachineEtatCourse.TERMINAUX.contains(statut)) {
                    etatStore.oublier(course.getId());
                }
            });
        }

        positionCourseRepository.saveAll(aEnregistrer);
        courseRepository.saveAll(aMettreAJour);
        return new ResultatIngestionDTO(aEnregistrer.size(), rejetes);
    }

    /**
     * Collects the stops revised across a batch without repeating one that
     * moved on several pings. The entities are mutated in place, so holding the
     * first reference already yields the final estimate.
     */
    private void fusionnerRevises(List<PassageGare> cible, List<PassageGare> nouveaux) {
        for (PassageGare passage : nouveaux) {
            if (!cible.contains(passage)) {
                cible.add(passage);
            }
        }
    }

    private PositionCourse versPosition(Course course, PingDTO ping, BigDecimal avancementKm,
                                        List<PassageGare> passages, OffsetDateTime etaSuivante) {
        PositionCourse position = new PositionCourse();
        position.setCourse(course);
        position.setHorodatage(ping.horodatage());
        position.setLatitude(ping.latitude());
        position.setLongitude(ping.longitude());
        position.setVitesseKmh(ping.vitesseKmh());
        position.setAvancementKm(avancementKm);
        position.setGarePrecedente(garePrecedente(passages, avancementKm));
        position.setGareSuivante(gareSuivante(passages, avancementKm));
        position.setEtaSuivante(etaSuivante);
        return position;
    }

    private Gare garePrecedente(List<PassageGare> passages, BigDecimal avancementKm) {
        PassageGare passage = CalculateurEta.arretPrecedent(passages, avancementKm);
        return passage == null ? null : passage.getGare();
    }

    private Gare gareSuivante(List<PassageGare> passages, BigDecimal avancementKm) {
        PassageGare passage = CalculateurEta.prochainArret(passages, avancementKm);
        return passage == null ? null : passage.getGare();
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
