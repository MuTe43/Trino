package tn.sncft.trino.circulation.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tn.sncft.trino.circulation.domaine.Course;
import tn.sncft.trino.circulation.domaine.Horaire;
import tn.sncft.trino.circulation.domaine.PassageGare;
import tn.sncft.trino.circulation.domaine.SensCourse;
import tn.sncft.trino.circulation.domaine.StatutCourse;
import tn.sncft.trino.circulation.repo.CourseRepository;
import tn.sncft.trino.circulation.repo.HoraireRepository;
import tn.sncft.trino.circulation.repo.PassageGareRepository;
import tn.sncft.trino.referentiel.domaine.Desserte;
import tn.sncft.trino.referentiel.domaine.Gare;
import tn.sncft.trino.referentiel.service.DesserteService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Materialises the standing timetable into dated runs: one {@link Course} per
 * active {@link Horaire} slot, and one {@link PassageGare} per stop of that
 * course's desserte.
 *
 * <p>Runs at startup and daily at 03:00 Africa/Tunis. Idempotent: the natural
 * key is (train, date_service, depart_theorique), so re-running a day already
 * generated inserts nothing.
 *
 * <p>Estimates start equal to the plan -- {@code arrivee_estimee =
 * arrivee_theorique} -- and are revised by the delay engine in phase 3. This
 * class computes no delay and sets no status beyond the initial A_QUAI.
 */
@Service
public class GenerateurCourses {

    private static final Logger log = LoggerFactory.getLogger(GenerateurCourses.class);

    /**
     * A timetable is written in local wall-clock time. Times are stored as
     * timestamptz in UTC; this is the zone they are resolved against.
     */
    private static final ZoneId ZONE_RESEAU = ZoneId.of("Africa/Tunis");

    private final HoraireRepository horaireRepository;
    private final CourseRepository courseRepository;
    private final PassageGareRepository passageGareRepository;
    private final DesserteService desserteService;

    public GenerateurCourses(HoraireRepository horaireRepository,
                             CourseRepository courseRepository,
                             PassageGareRepository passageGareRepository,
                             DesserteService desserteService) {
        this.horaireRepository = horaireRepository;
        this.courseRepository = courseRepository;
        this.passageGareRepository = passageGareRepository;
        this.desserteService = desserteService;
    }

    // These two carry @Transactional themselves, and that is load-bearing.
    // They call genererPour on `this`, which does not go through the Spring
    // proxy, so the @Transactional on genererPour alone would never start a
    // transaction: the two saveAll calls would commit separately and a failure
    // between them would leave courses with no passage_gare rows -- which the
    // idempotency check then makes permanent, because the course exists and is
    // never regenerated. Do not remove these as redundant.
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void genererAuDemarrage() {
        genererPour(aujourdhui());
    }

    @Scheduled(cron = "0 0 3 * * *", zone = "Africa/Tunis")
    @Transactional
    public void genererQuotidiennement() {
        genererPour(aujourdhui());
    }

    /**
     * Generates every missing course for a service date.
     *
     * @return the number of courses created
     */
    @Transactional
    public synchronized int genererPour(LocalDate date) {
        // Serialised because the whole day is materialised in one transaction:
        // if the 03:00 cron and a startup overlapped, the loser would hit the
        // (train, date, départ) unique constraint and roll back *every* course,
        // not just its own duplicate. A single instance is all this has to
        // survive; a second process would still need ON CONFLICT DO NOTHING.
        // The whole point of the @Transactional on the callers: courses and
        // their passages must commit together. Self-invocation silently
        // defeats the annotation, and nothing about the result looks different
        // when it does, so the condition is asserted rather than assumed.
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            log.error("Génération hors transaction : courses et passages pourraient être commités séparément.");
        }

        List<Horaire> horaires = horaireRepository.findActifs();
        if (horaires.isEmpty()) {
            log.warn("Aucun horaire actif : aucune course générée pour le {}.", date);
            return 0;
        }

        Set<String> dejaPresentes = new HashSet<>();
        for (CourseRepository.CleCourse cle : courseRepository.findClesByDateService(date)) {
            dejaPresentes.add(cleNaturelle(cle.getTrainId(), cle.getDepartTheorique()));
        }

        Map<Long, List<Desserte>> dessertesParLigne = new HashMap<>();
        List<Course> courses = new ArrayList<>();
        List<PassageGare> passages = new ArrayList<>();

        for (Horaire horaire : horaires) {
            OffsetDateTime departTheorique = date.atTime(horaire.getHeureDepart())
                    .atZone(ZONE_RESEAU)
                    .toOffsetDateTime();

            if (!dejaPresentes.add(cleNaturelle(horaire.getTrain().getId(), departTheorique))) {
                continue;
            }

            Long ligneId = horaire.getLigne().getId();
            List<Desserte> desserte = dessertesParLigne
                    .computeIfAbsent(ligneId, desserteService::parLigne);
            if (desserte.size() < 2) {
                log.warn("Ligne {} : desserte de {} arrêt(s), course ignorée.", ligneId, desserte.size());
                continue;
            }

            List<PlanArret> plan = planifier(desserte, horaire.getSens());
            Short duree = plan.get(plan.size() - 1).offsetArrivee();
            if (duree == null) {
                // A desserte whose terminus has no arrival offset (or whose
                // origin has no departure offset, which mirrors to the same
                // hole on a RETOUR) has no journey time. Unboxing it here used
                // to throw, and this runs from an ApplicationReadyEvent
                // listener -- an exception there aborts the whole startup, so
                // one malformed seed row took the API down rather than costing
                // one ligne.
                log.warn("Ligne {} sens {} : durée de trajet indéterminée, course ignorée.",
                        ligneId, horaire.getSens());
                continue;
            }
            short dureeMin = duree;

            Course course = new Course();
            course.setTrain(horaire.getTrain());
            course.setLigne(horaire.getLigne());
            course.setDateService(date);
            course.setSens(horaire.getSens());
            course.setDepartTheorique(departTheorique);
            course.setArriveeTheorique(departTheorique.plusMinutes(dureeMin));
            course.setStatut(StatutCourse.A_QUAI);
            course.setAvancementKm(BigDecimal.ZERO);
            courses.add(course);

            for (PlanArret arret : plan) {
                passages.add(versPassage(course, arret, departTheorique));
            }
        }

        if (courses.isEmpty()) {
            log.info("Courses du {} déjà générées, rien à faire.", date);
            return 0;
        }

        courseRepository.saveAll(courses);
        passageGareRepository.saveAll(passages);
        log.info("{} courses et {} passages générés pour le {}.", courses.size(), passages.size(), date);
        return courses.size();
    }

    private PassageGare versPassage(Course course, PlanArret arret, OffsetDateTime departTheorique) {
        PassageGare passage = new PassageGare();
        passage.setCourse(course);
        passage.setGare(arret.gare());
        passage.setOrdre(arret.ordre());
        passage.setPkKm(arret.pkKm());
        passage.setMargeMin(arret.margeMin());

        // Estimates start equal to the plan and are never null where a
        // theoretical time exists. They diverge only once phase 3 revises them.
        if (arret.offsetArrivee() != null) {
            OffsetDateTime arrivee = departTheorique.plusMinutes(arret.offsetArrivee());
            passage.setArriveeTheorique(arrivee);
            passage.setArriveeEstimee(arrivee);
        }
        if (arret.offsetDepart() != null) {
            OffsetDateTime depart = departTheorique.plusMinutes(arret.offsetDepart());
            passage.setDepartTheorique(depart);
            passage.setDepartEstimee(depart);
        }
        return passage;
    }

    /**
     * The stop plan for one direction.
     *
     * <p>The desserte is stored once, in the ALLER direction. A RETOUR course
     * walks it mirrored rather than duplicating every line of seed data: the
     * order reverses, chainage becomes {@code pkTotal - pk}, and each offset is
     * measured back from the total journey time. The arrival/departure swap is
     * what preserves dwell: a two-minute stop stays two minutes in both
     * directions.
     */
    private List<PlanArret> planifier(List<Desserte> desserte, SensCourse sens) {
        int n = desserte.size();
        Desserte dernier = desserte.get(n - 1);
        short total = dernier.getOffsetArriveeMin();
        BigDecimal pkTotal = dernier.getPkKm();

        List<PlanArret> plan = new ArrayList<>(n);
        for (int j = 0; j < n; j++) {
            if (sens == SensCourse.ALLER) {
                Desserte d = desserte.get(j);
                plan.add(new PlanArret(d.getGare(), (short) (j + 1), d.getPkKm(), d.getMargeMin(),
                        d.getOffsetArriveeMin(), d.getOffsetDepartMin()));
            } else {
                Desserte d = desserte.get(n - 1 - j);
                // The segment arriving at this stop on the way back is the one
                // that departed from it on the way out, so its slack is held by
                // the next stop in ALLER order.
                short marge = (n - j) < n ? desserte.get(n - j).getMargeMin() : (short) 0;
                plan.add(new PlanArret(
                        d.getGare(),
                        (short) (j + 1),
                        pkTotal.subtract(d.getPkKm()),
                        marge,
                        d.getOffsetDepartMin() == null ? null : (short) (total - d.getOffsetDepartMin()),
                        d.getOffsetArriveeMin() == null ? null : (short) (total - d.getOffsetArriveeMin())));
            }
        }
        return plan;
    }

    private LocalDate aujourdhui() {
        return LocalDate.now(ZONE_RESEAU);
    }

    private String cleNaturelle(Long trainId, OffsetDateTime departTheorique) {
        return trainId + "@" + departTheorique.toInstant();
    }

    private record PlanArret(Gare gare, short ordre, BigDecimal pkKm, short margeMin,
                             Short offsetArrivee, Short offsetDepart) {
    }
}
