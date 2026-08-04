package tn.sncft.trino.circulation.service;

import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.sncft.trino.circulation.domaine.ClasseRetard;
import tn.sncft.trino.circulation.domaine.Course;
import tn.sncft.trino.circulation.domaine.PassageGare;
import tn.sncft.trino.circulation.domaine.PositionCourse;
import tn.sncft.trino.circulation.domaine.StatutCourse;
import tn.sncft.trino.circulation.dto.CourseResumeDTO;
import tn.sncft.trino.circulation.dto.PassageDTO;
import tn.sncft.trino.circulation.dto.PositionDTO;
import tn.sncft.trino.circulation.repo.CourseRepository;
import tn.sncft.trino.circulation.repo.PassageGareRepository;
import tn.sncft.trino.circulation.repo.PositionCourseRepository;
import tn.sncft.trino.commun.PageableUtils;
import tn.sncft.trino.commun.RessourceIntrouvableException;
import tn.sncft.trino.referentiel.domaine.Gare;
import tn.sncft.trino.referentiel.domaine.Ligne;
import tn.sncft.trino.referentiel.domaine.Train;
import tn.sncft.trino.referentiel.domaine.TypeTrain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Read side of circulation. Combines what is in the database (the timetable,
 * the observed times, the estimates) with what is only in memory (where each
 * train currently is), which is why the live fields of
 * {@link CourseResumeDTO} are null for a course that has not reported.
 *
 * <p>Nothing here computes an expected time by adding a delay to a theoretical
 * one. The estimates were written by {@link MoteurRetard} and are read back as
 * they stand -- that is what keeps the map panel, the station page and the
 * kiosk board from drifting apart.
 */
@Service
public class CourseService {

    private static final ZoneId ZONE_RESEAU = ZoneId.of("Africa/Tunis");

    private final CourseRepository courseRepository;
    private final PassageGareRepository passageGareRepository;
    private final PositionCourseRepository positionCourseRepository;
    private final EtatCirculationStore etatStore;
    private final CalculateurEta calculateurEta;

    public CourseService(CourseRepository courseRepository,
                         PassageGareRepository passageGareRepository,
                         PositionCourseRepository positionCourseRepository,
                         EtatCirculationStore etatStore,
                         CalculateurEta calculateurEta) {
        this.courseRepository = courseRepository;
        this.passageGareRepository = passageGareRepository;
        this.positionCourseRepository = positionCourseRepository;
        this.etatStore = etatStore;
        this.calculateurEta = calculateurEta;
    }

    @Transactional(readOnly = true)
    public Page<CourseResumeDTO> lister(LocalDate date, Long ligneId, Long gareId, StatutCourse statut,
                                        TypeTrain type, String q, int page, int taille) {
        Page<Course> courses = courseRepository.rechercher(
                date == null ? LocalDate.now(ZONE_RESEAU) : date,
                ligneId, gareId, statut, type, motif(q),
                PageableUtils.de(page, taille));

        Map<Long, List<PassageGare>> passages = chargerPassages(
                courses.getContent().stream().map(Course::getId).toList());

        return courses.map(course ->
                versResume(course, passages.getOrDefault(course.getId(), List.of())));
    }

    /** Unified search. Same query as {@link #lister}, with only {@code q} bound. */
    @Transactional(readOnly = true)
    public Page<CourseResumeDTO> rechercher(String q, LocalDate date, int page, int taille) {
        return lister(date, null, null, null, null, q, page, taille);
    }

    @Transactional(readOnly = true)
    public CourseResumeDTO trouverParId(Long id) {
        Course course = courseRepository.findAvecLigneEtTrain(id)
                .orElseThrow(() -> new RessourceIntrouvableException("Course " + id + " introuvable."));
        return versResume(course, passageGareRepository.findByCourseId(id));
    }

    @Transactional(readOnly = true)
    public List<PassageDTO> passages(Long courseId) {
        List<PassageGare> passages = passageGareRepository.findByCourseId(courseId);
        if (passages.isEmpty() && !courseRepository.existsById(courseId)) {
            throw new RessourceIntrouvableException("Course " + courseId + " introuvable.");
        }
        return passages.stream().map(CourseService::versPassage).toList();
    }

    @Transactional(readOnly = true)
    public List<PositionDTO> positions(Long courseId, OffsetDateTime depuis) {
        if (!courseRepository.existsById(courseId)) {
            throw new RessourceIntrouvableException("Course " + courseId + " introuvable.");
        }
        return positionCourseRepository.findHistorique(courseId, depuis).stream()
                .map(CourseService::versPosition)
                .toList();
    }

    private CourseResumeDTO versResume(Course course, List<PassageGare> passages) {
        Train train = course.getTrain();
        Ligne ligne = course.getLigne();
        EtatCirculation etat = etatStore.lire(course.getId()).orElse(null);

        CourseResumeDTO.PositionCouranteDTO position = null;
        CourseResumeDTO.GareBreveDTO precedente = null;
        CourseResumeDTO.GareBreveDTO suivante = null;
        OffsetDateTime eta = null;

        // Hot state is memory only, so it is empty after a restart even for a
        // course that has been running all morning. The chainage survives on
        // the course row, so which stations it is between is still answerable;
        // only the exact fix and the ETA are lost until the next ping.
        if (course.getDernierePositionAt() != null) {
            BigDecimal avancement = etat != null ? etat.dernier().avancementKm() : course.getAvancementKm();
            precedente = versGareBreve(CalculateurEta.arretPrecedent(passages, avancement));
            suivante = versGareBreve(CalculateurEta.prochainArret(passages, avancement));
        }
        if (etat != null) {
            FixPosition fix = etat.dernier();
            position = new CourseResumeDTO.PositionCouranteDTO(
                    fix.latitude(), fix.longitude(), fix.vitesseKmh());
            eta = calculateurEta.pour(course, passages, etat);
        }

        return new CourseResumeDTO(
                course.getId(),
                train.getNumero(),
                train.getNom(),
                train.getType(),
                new CourseResumeDTO.LigneBreveDTO(ligne.getId(), ligne.getNom()),
                course.getSens(),
                course.getStatut(),
                course.getRetardMin(),
                ClasseRetard.de(course.getRetardMin()),
                course.getCauseRetard(),
                course.getDepartTheorique(),
                course.getArriveeTheorique(),
                position,
                precedente,
                suivante,
                eta);
    }

    static PassageDTO versPassage(PassageGare passage) {
        Gare gare = passage.getGare();
        return new PassageDTO(
                passage.getOrdre(),
                new PassageDTO.GareBreveDTO(gare.getId(), gare.getNom()),
                passage.getQuai(),
                passage.getArriveeTheorique(),
                passage.getArriveeEstimee(),
                passage.getArriveeReelle(),
                passage.getDepartTheorique(),
                passage.getDepartEstimee(),
                passage.getDepartReelle(),
                passage.getRetardMin(),
                ClasseRetard.de(passage.getRetardMin()),
                passage.getArriveeReelle() != null);
    }

    private static PositionDTO versPosition(PositionCourse position) {
        return new PositionDTO(
                position.getHorodatage(),
                position.getLatitude(),
                position.getLongitude(),
                position.getVitesseKmh(),
                position.getAvancementKm(),
                position.getGarePrecedente() == null ? null : position.getGarePrecedente().getId(),
                position.getGareSuivante() == null ? null : position.getGareSuivante().getId(),
                position.getEtaSuivante());
    }

    private CourseResumeDTO.GareBreveDTO versGareBreve(PassageGare passage) {
        return passage == null
                ? null
                : new CourseResumeDTO.GareBreveDTO(passage.getGare().getId(), passage.getGare().getNom());
    }

    /** Null when there is nothing to search for, so the query drops the clause. */
    private String motif(String q) {
        return q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
    }

    private Map<Long, List<PassageGare>> chargerPassages(Collection<Long> courseIds) {
        if (courseIds.isEmpty()) {
            return Map.of();
        }
        return passageGareRepository.findByCourseIds(courseIds).stream()
                .collect(Collectors.groupingBy(passage -> passage.getCourse().getId(),
                        HashMap::new, Collectors.toList()));
    }
}
