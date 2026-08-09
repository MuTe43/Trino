package tn.sncft.trino.circulation.backfill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.sncft.trino.circulation.domaine.Course;
import tn.sncft.trino.circulation.domaine.PassageGare;
import tn.sncft.trino.circulation.domaine.StatutCourse;
import tn.sncft.trino.circulation.repo.CourseRepository;
import tn.sncft.trino.circulation.repo.PassageGareRepository;
import tn.sncft.trino.circulation.service.GenerateurCourses;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Writes one finished service date: materialises the day through
 * {@link GenerateurCourses}, then stamps real times and delays on it as though
 * the delay engine had tracked it live.
 *
 * <p>Separate bean from {@link BackfillHistorique} on purpose. The runner has to
 * call this through the Spring proxy for {@code @Transactional} to apply; a
 * private method on the runner itself would be a self-invocation and would
 * silently commit the generation and the stamping separately -- the exact trap
 * {@code GenerateurCourses} documents at its own transactional entry points.
 *
 * <p>No {@code position_course} rows are written. The charts read stamped
 * passages, not the feed, and a fabricated ping history would be both large and
 * misleading -- it would suggest positions that were never observed.
 *
 * <p>{@code statut} is set here rather than by {@code MachineEtatCourse}, which
 * is the single writer of that column on the live path. A synthesised past day
 * is by definition not on that path: there is no feed to drive the state machine
 * and every course is finished before the row exists.
 */
@Service
public class HistorisateurJournee {

    private static final Logger log = LoggerFactory.getLogger(HistorisateurJournee.class);

    /**
     * Mixed into every per-course seed. Changing it reshuffles the whole
     * synthesised history, so it is a constant rather than anything derived
     * from the clock: the demo's numbers must not move between runs.
     */
    private static final long GRAINE_BASE = 0x7217_0000L;

    private final GenerateurCourses generateurCourses;
    private final CourseRepository courseRepository;
    private final PassageGareRepository passageGareRepository;

    public HistorisateurJournee(GenerateurCourses generateurCourses,
                                CourseRepository courseRepository,
                                PassageGareRepository passageGareRepository) {
        this.generateurCourses = generateurCourses;
        this.courseRepository = courseRepository;
        this.passageGareRepository = passageGareRepository;
    }

    /**
     * Generates the date if it is missing, then stamps every course of it.
     *
     * <p>Idempotent by construction rather than by a "already done" flag: the
     * generator skips slots that exist (its natural key is train + date +
     * départ), and the stamping is a pure function of that key through the
     * seed, so a second run recomputes byte-identical values. That also makes
     * it self-healing if a previous run was interrupted half way.
     *
     * @return the number of courses stamped
     */
    @Transactional
    public int historiser(LocalDate date) {
        generateurCourses.genererPour(date);

        List<Course> courses = courseRepository.findParDateService(date);
        if (courses.isEmpty()) {
            log.warn("Aucune course pour le {} : rien à historiser.", date);
            return 0;
        }

        Map<Long, List<PassageGare>> passagesParCourse = new HashMap<>();
        for (PassageGare passage : passageGareRepository.findByCourseIds(
                courses.stream().map(Course::getId).toList())) {
            passagesParCourse.computeIfAbsent(passage.getCourse().getId(), cle -> new ArrayList<>()).add(passage);
        }

        List<PassageGare> modifies = new ArrayList<>();
        for (Course course : courses) {
            // A cancelled run did not finish at its terminus, and it has no real
            // times to stamp. Skipping it also means a cancellation set by hand
            // -- or, from phase 6, by the incident workflow -- survives a
            // re-run instead of being quietly turned into a completed course.
            if (course.getStatut() == StatutCourse.ANNULE) {
                continue;
            }
            List<PassageGare> passages = passagesParCourse.getOrDefault(course.getId(), List.of());
            if (passages.isEmpty()) {
                log.warn("Course {} sans passage : ignorée.", course.getId());
                continue;
            }
            passages.sort(Comparator.comparing(PassageGare::getOrdre));
            modifies.addAll(historiserCourse(course, passages));
        }

        courseRepository.saveAll(courses);
        passageGareRepository.saveAll(modifies);
        log.info("{} courses historisées pour le {}.", courses.size(), date);
        return courses.size();
    }

    private List<PassageGare> historiserCourse(Course course, List<PassageGare> passages) {
        OffsetDateTime depart = course.getDepartTheorique();
        double dureeMin = minutesEntre(depart, course.getArriveeTheorique());

        Random rnd = ModelePerturbation.aleatoire(graine(course));
        List<ModelePerturbation.Incident> incidents = ModelePerturbation.tirer(rnd, dureeMin);

        int retardFinal = 0;
        OffsetDateTime dernierInstant = depart;
        for (PassageGare passage : passages) {
            Integer retardArrivee = null;
            if (passage.getArriveeTheorique() != null) {
                double perte = ModelePerturbation.perteCumulee(
                        incidents, minutesEntre(depart, passage.getArriveeTheorique()));
                OffsetDateTime reelle = passage.getArriveeTheorique().plusSeconds(Math.round(perte));
                passage.setArriveeReelle(reelle);
                // The estimate freezes at the observed time once the stop is
                // passed (domain-model.md, "the three times"). Never null here:
                // it is set exactly where its theoretical counterpart exists,
                // which is what chk_passage_estimee_suit_theorique requires.
                passage.setArriveeEstimee(reelle);
                retardArrivee = (int) Math.round(perte / 60.0);
                dernierInstant = reelle;
            }
            Integer retardDepart = null;
            if (passage.getDepartTheorique() != null) {
                double perte = ModelePerturbation.perteCumulee(
                        incidents, minutesEntre(depart, passage.getDepartTheorique()));
                OffsetDateTime reelle = passage.getDepartTheorique().plusSeconds(Math.round(perte));
                passage.setDepartReelle(reelle);
                passage.setDepartEstimee(reelle);
                retardDepart = (int) Math.round(perte / 60.0);
                dernierInstant = reelle;
            }

            // A stop's delay is the arrival's where there is one -- the origin
            // has only a departure, and that is the one figure it can carry.
            int retard = retardArrivee != null ? retardArrivee : (retardDepart != null ? retardDepart : 0);
            passage.setRetardMin(retard);
            retardFinal = retard;
        }

        course.setStatut(StatutCourse.TERMINUS_ATTEINT);
        course.setRetardMin(retardFinal);
        course.setCauseRetard(retardFinal > 0 ? ModelePerturbation.tirerCause(rnd) : null);
        course.setAvancementKm(passages.get(passages.size() - 1).getPkKm());
        course.setDernierePositionAt(dernierInstant);
        return passages;
    }

    /**
     * Seeded from the course's natural key, not from a counter: the same run on
     * the same date always draws the same perturbations, whatever order the
     * courses come back in and whichever dates were backfilled alongside it.
     * {@code String.hashCode} is specified by the language, so this is stable
     * across JVMs and machines -- a demo reset reproduces the same numbers.
     */
    private long graine(Course course) {
        String cle = course.getTrain().getId() + "@" + course.getDateService()
                + "@" + course.getDepartTheorique().toInstant();
        return GRAINE_BASE * 31 + cle.hashCode();
    }

    private static double minutesEntre(OffsetDateTime debut, OffsetDateTime fin) {
        if (debut == null || fin == null) {
            return 0;
        }
        return Duration.between(debut, fin).toSeconds() / 60.0;
    }
}
