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
import java.time.LocalTime;
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

    /** Every status there is: what "no filter" is expressed as at the query boundary. */
    private static final List<StatutCourse> TOUS_LES_STATUTS = List.of(StatutCourse.values());

    @Transactional(readOnly = true)
    public Page<CourseResumeDTO> lister(LocalDate date, Long ligneId, Long gareId, List<StatutCourse> statuts,
                                        TypeTrain type, String q, CriteresRecherche criteres,
                                        int page, int taille) {
        LocalDate jour = date == null ? LocalDate.now(ZONE_RESEAU) : date;
        CriteresRecherche filtres = criteres == null ? CriteresRecherche.aucun() : criteres;

        Page<Course> courses = courseRepository.rechercher(
                jour, ligneId, gareId, statutsOuTous(statuts), type, motif(q),
                motif(filtres.region()),
                motif(filtres.destination()),
                borneBasse(jour, filtres.heureDebut()),
                borneHaute(jour, filtres.heureFin()),
                PageableUtils.de(page, taille));

        Map<Long, List<PassageGare>> passages = chargerPassages(
                courses.getContent().stream().map(Course::getId).toList());

        return courses.map(course ->
                versResume(course, passages.getOrDefault(course.getId(), List.of())));
    }

    /**
     * Unified search: the same query as {@link #lister} with the criteria §4.9
     * of the cahier des charges asks for.
     *
     * <p>Every criterion is optional, {@code q} included. Phase 9 added region,
     * destination and the departure window, and a caller filtering on region
     * alone has nothing to put in {@code q} -- requiring it would have made the
     * three new criteria unreachable without also naming a train.
     */
    @Transactional(readOnly = true)
    public Page<CourseResumeDTO> rechercher(String q, LocalDate date, CriteresRecherche criteres,
                                            int page, int taille) {
        return lister(date, null, null, null, null, q, criteres, page, taille);
    }

    /**
     * The optional search criteria added in phase 9, grouped so the two list
     * methods do not grow four more positional parameters each -- at eleven
     * arguments an accidental transposition of two adjacent strings compiles
     * and silently searches the wrong column.
     *
     * <p>{@code heureDebut}/{@code heureFin} are wall-clock times in
     * Africa/Tunis, resolved against the service date by {@link #instant}.
     */
    public record CriteresRecherche(String region, String destination,
                                    LocalTime heureDebut, LocalTime heureFin) {

        public static CriteresRecherche aucun() {
            return new CriteresRecherche(null, null, null, null);
        }
    }

    /**
     * Resolves a requested wall-clock time against the service date, falling
     * back to a bound of that date when the caller named none.
     *
     * <p>Two separate things are going on here, and both are load-bearing.
     *
     * <p>The conversion: a timetable is written in local time and the column is a
     * timestamptz in UTC (invariant 6), so "06:00 to 09:00" has to become two
     * instants in Africa/Tunis before it can be compared to anything. Binding the
     * bare time would compare local hours against UTC ones and shift every result
     * by the network's offset -- an hour of departures wrong, with nothing in the
     * response to show it.
     *
     * <p>The fallback: an unset bound becomes a substitute rather than a null.
     * Postgres cannot infer a type for a parameter that only ever appears in
     * {@code ? is null}, so binding null there made every call to
     * {@code /recherche} a 500 -- see {@code CourseRepository.CRITERES}.
     *
     * <p>The substitute is a <em>whole day beyond</em> the service date on each
     * side, not the start and end of the date itself. Both are wide enough today,
     * because {@code GenerateurCourses} builds {@code departTheorique} from the
     * same date in the same zone -- but "wide enough because two unrelated pieces
     * of code happen to agree" is a constraint nobody declared, on the endpoint
     * that feeds {@code /courses} and the map. Any course whose departure fell
     * outside its own local day would have vanished from every listing with no
     * error. A ±1 day margin cannot exclude a row whatever the offset, so the
     * default window is provably not a filter.
     */
    private OffsetDateTime borneBasse(LocalDate date, LocalTime heure) {
        return heure != null
                ? date.atTime(heure).atZone(ZONE_RESEAU).toOffsetDateTime()
                : date.minusDays(1).atStartOfDay(ZONE_RESEAU).toOffsetDateTime();
    }

    private OffsetDateTime borneHaute(LocalDate date, LocalTime heure) {
        return heure != null
                ? date.atTime(heure).atZone(ZONE_RESEAU).toOffsetDateTime()
                : date.plusDays(1).atTime(LocalTime.MAX).atZone(ZONE_RESEAU).toOffsetDateTime();
    }

    /**
     * "No filter" is passed down as every known status, never as a null or
     * empty collection: {@code course.statut} is a not-null column, so
     * matching every value of the enum is exactly equivalent to not filtering
     * at all, and it sidesteps binding an empty/null collection to a JPQL
     * {@code in} clause, which Hibernate does not handle reliably.
     */
    private List<StatutCourse> statutsOuTous(List<StatutCourse> statuts) {
        return (statuts == null || statuts.isEmpty()) ? TOUS_LES_STATUTS : statuts;
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
