package tn.sncft.trino.circulation.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tn.sncft.trino.circulation.domaine.Course;
import tn.sncft.trino.circulation.domaine.PassageGare;
import tn.sncft.trino.circulation.domaine.StatutCourse;
import tn.sncft.trino.circulation.repo.CourseRepository;
import tn.sncft.trino.circulation.repo.PassageGareRepository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What makes the system degrade honestly instead of showing stale positions
 * forever. Two jobs, both of which only a clock can notice:
 *
 * <ol>
 *   <li><b>The feed died mid-run.</b> A running course whose last ping is older
 *       than 90 s moves to {@code ARRET_EXCEPTIONNEL}. Without this the map
 *       keeps a dead train frozen on the line and calls it EN_CIRCULATION.</li>
 *   <li><b>The trainset never showed up.</b> An {@code A_QUAI} course whose
 *       scheduled departure passed more than 5 minutes ago with no ping at all
 *       moves to {@code RETARDE}, and its delay is propagated so every
 *       downstream estimate shifts.</li>
 * </ol>
 *
 * <p>Job 2 is the one that is easy to miss, because nothing wakes the engine --
 * there is no ping to react to. Skip it and a 14:00 departure whose train never
 * arrives shows as on time on the platform board while the platform is empty.
 *
 * <p>This class never assigns a status itself. It reports the passage of time
 * to {@link MachineEtatCourse}, which stays the single writer.
 */
@Component
public class DetecteurSilence {

    private static final Logger log = LoggerFactory.getLogger(DetecteurSilence.class);

    private static final ZoneId ZONE_RESEAU = ZoneId.of("Africa/Tunis");

    /** A closed run has nothing left to detect. */
    private static final Set<StatutCourse> STATUTS_CLOS =
            EnumSet.of(StatutCourse.ANNULE, StatutCourse.TERMINUS_ATTEINT);

    private final CourseRepository courseRepository;
    private final PassageGareRepository passageGareRepository;
    private final MachineEtatCourse machineEtatCourse;
    private final MoteurRetard moteurRetard;
    private final DiffuseurCirculation diffuseur;
    private final EtatCirculationStore etatStore;
    private final HorlogeCirculation horloge;

    public DetecteurSilence(CourseRepository courseRepository,
                            PassageGareRepository passageGareRepository,
                            MachineEtatCourse machineEtatCourse,
                            MoteurRetard moteurRetard,
                            DiffuseurCirculation diffuseur,
                            EtatCirculationStore etatStore,
                            HorlogeCirculation horloge) {
        this.courseRepository = courseRepository;
        this.passageGareRepository = passageGareRepository;
        this.machineEtatCourse = machineEtatCourse;
        this.moteurRetard = moteurRetard;
        this.diffuseur = diffuseur;
        this.etatStore = etatStore;
        this.horloge = horloge;
    }

    @Scheduled(fixedDelay = 30_000)
    @Transactional
    public void balayer() {
        // The service date comes from the wall clock because that is what
        // GenerateurCourses materialised the day against. The comparisons below
        // use the network clock, which is the feed's -- see HorlogeCirculation.
        LocalDate jour = LocalDate.now(ZONE_RESEAU);
        OffsetDateTime maintenant = horloge.maintenant();

        List<Course> courses = courseRepository.findDuJourSaufStatuts(jour, STATUTS_CLOS);
        if (courses.isEmpty()) {
            return;
        }
        Map<Long, List<PassageGare>> passagesParCourse = chargerPassages(
                courses.stream().map(Course::getId).toList());

        int transitions = 0;
        for (Course course : courses) {
            List<PassageGare> passages = passagesParCourse.getOrDefault(course.getId(), List.of());
            try {
                // Per course, like the ingestion path: one malformed run must
                // not roll back the sweep for the other ~79 and leave the whole
                // network showing stale positions.
                if (traiter(course, passages, maintenant)) {
                    transitions++;
                }
            } catch (RuntimeException e) {
                log.warn("Course {} ignorée pendant le balayage : {}", course.getId(), e.toString());
            }
        }

        if (transitions > 0) {
            log.debug("Balayage : {} transition(s) de statut sur {} course(s).", transitions, courses.size());
        }
        // Entities are managed inside this transaction, so the status, delay
        // and revised estimates flush on commit without an explicit save.
    }

    /** @return whether the status changed */
    private boolean traiter(Course course, List<PassageGare> passages, OffsetDateTime maintenant) {
        Optional<StatutCourse> change = machineEtatCourse.evaluer(course, passages, maintenant);

        if (course.getDernierePositionAt() == null) {
            if (course.getStatut() == StatutCourse.RETARDE) {
                appliquerRetardAvantDepart(course, passages, MoteurRetard.minutesEntre(
                        course.getDepartTheorique(), maintenant));
            } else if (course.getStatut() == StatutCourse.A_QUAI && course.getRetardMin() != 0) {
                // A course back at A_QUAI is not late any more, but the delay
                // job 2 propagated is still sitting on every downstream stop.
                // Nothing else will clear it -- job 2 only runs while the
                // course is RETARDE -- so the board would keep advertising a
                // delay for a train that is not late until its origin
                // departure is finally stamped. Wind it back.
                appliquerRetardAvantDepart(course, passages, 0);
            }
        }

        change.ifPresent(statut -> {
            diffuseur.statut(course, passages, statut);
            if (statut == StatutCourse.TERMINUS_ATTEINT) {
                etatStore.oublier(course.getId());
            }
        });
        return change.isPresent();
    }

    /**
     * Job 2's arithmetic. The delay is measured against the clock rather than
     * against an observation, because there is no observation -- then pushed
     * through the same propagation a ping would have triggered, so the whole
     * downstream board shifts instead of just the departure row.
     */
    private void appliquerRetardAvantDepart(Course course, List<PassageGare> passages, int retard) {
        if (retard == course.getRetardMin()) {
            return;
        }
        course.setRetardMin(retard);
        List<PassageGare> revises = moteurRetard.propager(passages, retard, (short) 0);
        diffuseur.retard(course, passages, revises);
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
