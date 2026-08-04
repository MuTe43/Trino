package tn.sncft.trino.simulateur.moteur;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tn.sncft.trino.simulateur.client.ClientTrino;
import tn.sncft.trino.simulateur.config.ProprietesSimulateur;
import tn.sncft.trino.simulateur.dto.CourseDuJourDTO;
import tn.sncft.trino.simulateur.dto.PingDTO;
import tn.sncft.trino.simulateur.dto.ResultatIngestionDTO;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * The tick. Every {@code tick-secondes} of wall clock:
 *
 * <ol>
 *   <li>any course whose theoretical departure has passed becomes active at km 0;</li>
 *   <li>each active course advances by its speed over the elapsed simulated time;</li>
 *   <li>{@link ProfilPerturbation} occasionally slows one down or holds it at a stop;</li>
 *   <li>the whole batch is POSTed in one request.</li>
 * </ol>
 *
 * <p>All state is in memory. This process never opens a database connection --
 * it is a stand-in for GPS hardware, and hardware does not write to your
 * schema (invariant 3).
 */
@Component
public class MoteurSimulation {

    private static final Logger log = LoggerFactory.getLogger(MoteurSimulation.class);
    private static final ZoneId ZONE_RESEAU = ZoneId.of("Africa/Tunis");
    private static final int TAILLE_LOT_MAX = 500;

    private final ClientTrino client;
    private final ProfilPerturbation profil;
    private final ProprietesSimulateur.Simulateur config;

    private final Map<Long, EtatCourseSimulee> etats = new LinkedHashMap<>();

    private Instant ancreReelle;
    private OffsetDateTime ancreSimulee;
    private Instant dernierTick;
    private long compteurTicks;

    public MoteurSimulation(ClientTrino client, ProfilPerturbation profil, ProprietesSimulateur proprietes) {
        this.client = client;
        this.profil = profil;
        this.config = proprietes.getSimulateur();
    }

    @Scheduled(fixedDelayString = "${trino.simulateur.tick-secondes:5}", timeUnit = TimeUnit.SECONDS)
    public void tick() {
        // A @Scheduled method that throws is silently unscheduled by Spring:
        // the feed would stop dead with one stack trace and no further sign.
        try {
            executerTick();
        } catch (RuntimeException e) {
            log.error("Tick en échec, la simulation continue : {}", e.getMessage(), e);
        }
    }

    private void executerTick() {
        Instant maintenant = Instant.now();
        if (ancreReelle == null) {
            demarrerHorloge(maintenant);
        }

        if (etats.isEmpty() || compteurTicks % Math.max(1, config.getTicksParRechargement()) == 0) {
            recharger();
        }
        compteurTicks++;

        double secondesSimulees = Duration.between(dernierTick, maintenant).toMillis()
                / 1000.0 * config.getAcceleration();
        dernierTick = maintenant;
        OffsetDateTime maintenantSimule = maintenantSimule(maintenant);

        List<PingDTO> pings = new ArrayList<>();
        for (EtatCourseSimulee etat : etats.values()) {
            if (etat.estTerminee()) {
                continue;
            }
            if (etat.doitPartir(maintenantSimule)) {
                etat.demarrer();
            }
            if (!etat.estPartie()) {
                continue;
            }

            if (etat.avancer(secondesSimulees, profil)) {
                // The weighted cause draw is only meaningful if it is visible
                // somewhere. It cannot ride on the ping: the contract carries
                // coordinates and a speed, because that is what real AVL
                // hardware sends. Phase 3 decides course.cause_retard from the
                // observed delay; this line is what lets you check the demo's
                // causes look plausible.
                log.info("Course {} perturbée : {} ({})",
                        etat.courseId(), etat.perturbation(), etat.cause());
            }
            double[] position = etat.position();
            pings.add(new PingDTO(etat.courseId(), maintenantSimule, position[0], position[1], etat.vitesseKmh()));
        }

        publier(pings, maintenantSimule);
    }

    private void publier(List<PingDTO> pings, OffsetDateTime maintenantSimule) {
        if (pings.isEmpty()) {
            return;
        }
        int acceptes = 0;
        int rejetes = 0;
        for (int debut = 0; debut < pings.size(); debut += TAILLE_LOT_MAX) {
            List<PingDTO> lot = pings.subList(debut, Math.min(debut + TAILLE_LOT_MAX, pings.size()));
            ResultatIngestionDTO resultat = client.publier(lot);
            acceptes += resultat.acceptes();
            rejetes += resultat.rejetes();
        }
        log.info("t={} : {} position(s) acceptée(s), {} rejetée(s), {} course(s) en circulation.",
                maintenantSimule.toLocalTime().withNano(0), acceptes, rejetes, pings.size());
    }

    private void recharger() {
        List<CourseDuJourDTO> courses = client.chargerCoursesDuJour();
        if (courses.isEmpty()) {
            return;
        }
        // Drop anything the API no longer lists -- finished runs and, at the
        // 03:00 rollover, the whole of yesterday. Without this the map grows
        // by a service day every day the process stays up.
        //
        // A course still in motion is never dropped, even if this poll omitted
        // it. courses-du-jour skips a course whose geometry it cannot build,
        // so a single bad response would otherwise discard a running train's
        // state, and the next poll would re-create it at km 0 -- the train
        // teleporting back to its origin mid-run.
        Set<Long> vivantes = courses.stream().map(CourseDuJourDTO::courseId).collect(Collectors.toSet());
        etats.entrySet().removeIf(entree ->
                !vivantes.contains(entree.getKey()) && !entree.getValue().estEnCours());

        int nouvelles = 0;
        for (CourseDuJourDTO course : courses) {
            if (etats.containsKey(course.courseId())) {
                continue;
            }
            try {
                etats.put(course.courseId(), new EtatCourseSimulee(course, profil.seraPerturbee()));
                nouvelles++;
            } catch (RuntimeException e) {
                log.warn("Course {} ignorée : {}", course.courseId(), e.getMessage());
            }
        }
        if (nouvelles > 0) {
            log.info("{} nouvelle(s) course(s) chargée(s), {} suivie(s) au total.", nouvelles, etats.size());
        }
    }

    /**
     * Anchors the simulated clock. At acceleration 1.0 simulated time is real
     * time. Above that, {@code heure-debut} decides where the day starts --
     * without it, an accelerated run started at 14:00 would skip every morning
     * departure before it had a chance to move.
     */
    private void demarrerHorloge(Instant maintenant) {
        this.ancreReelle = maintenant;
        this.dernierTick = maintenant;

        String heureDebut = config.getHeureDebut();
        if (heureDebut == null || heureDebut.isBlank()) {
            this.ancreSimulee = OffsetDateTime.now(ZONE_RESEAU);
        } else {
            this.ancreSimulee = LocalDate.now(ZONE_RESEAU)
                    .atTime(LocalTime.parse(heureDebut.trim()))
                    .atZone(ZONE_RESEAU)
                    .toOffsetDateTime();
        }
        log.info("Horloge simulée démarrée à {} (accélération x{}).",
                ancreSimulee.toLocalTime(), config.getAcceleration());
    }

    private OffsetDateTime maintenantSimule(Instant maintenant) {
        long ecoulesMs = Duration.between(ancreReelle, maintenant).toMillis();
        long simulesMs = (long) (ecoulesMs * config.getAcceleration());
        return ancreSimulee.plusNanos(simulesMs * 1_000_000L);
    }
}
