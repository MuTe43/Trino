package tn.sncft.trino.circulation.charge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tn.sncft.trino.circulation.service.GenerateurCourses;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * Builds the service day the load test runs against: several hundred courses
 * whose journeys overlap, so the peak is concurrency and not daily volume.
 *
 * <p>Profile-gated so it can never fire during a normal API run — it deletes and
 * rewrites a fleet, which is not something an operator should be able to trigger
 * by accident. Invoked by {@code scripts/charge.sh}:
 *
 * <pre>
 * scripts/charge.sh                 # 320 trains leaving between 05:00 and 05:20
 * scripts/charge.sh --trains=500
 * scripts/charge.sh --nettoyer      # put the database back
 * </pre>
 *
 * <p>Runs against today only. The load profile has to be the day the simulator
 * is driving, and the simulator only ever reads {@code courses-du-jour}.
 */
@Component
@Profile("charge")
public class ProfilCharge implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ProfilCharge.class);

    /** Timetable dates are local wall-clock, same zone as GenerateurCourses. */
    private static final ZoneId ZONE_RESEAU = ZoneId.of("Africa/Tunis");

    /**
     * 320 on top of the 80 the seed already materialises. The specification says
     * <em>plusieurs centaines</em>; 400 courses on the day clears that with the
     * margin needed for the ones that have already reached their terminus by the
     * time the measurement window opens.
     */
    private static final int TRAINS_PAR_DEFAUT = 320;

    /**
     * Early enough that the whole synthetic fleet is still running when the
     * seeded morning peak starts, so the two loads overlap instead of queueing.
     */
    private static final String DEPART_PAR_DEFAUT = "05:00";

    /**
     * Under the 35-minute shortest desserte on the network, so every departure in
     * the window is still in circulation when the last one leaves. Widening this
     * past 35 lowers the peak without lowering the course count — which is
     * exactly the mistake this measurement exists to avoid.
     */
    private static final int ETALEMENT_PAR_DEFAUT = 20;

    private final FlotteCharge flotteCharge;
    private final GenerateurCourses generateurCourses;
    private final ConfigurableApplicationContext contexte;

    public ProfilCharge(FlotteCharge flotteCharge,
                        GenerateurCourses generateurCourses,
                        ConfigurableApplicationContext contexte) {
        this.flotteCharge = flotteCharge;
        this.generateurCourses = generateurCourses;
        this.contexte = contexte;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            if (args.containsOption("nettoyer")) {
                int supprimes = flotteCharge.supprimer();
                log.info("Profil de charge retiré : {} train(s) supprimé(s).", supprimes);
                return;
            }

            int trains = entier(args, "trains", TRAINS_PAR_DEFAUT);
            LocalTime depart = heure(args, "depart", DEPART_PAR_DEFAUT);
            int etalement = entier(args, "etalement", ETALEMENT_PAR_DEFAUT);

            // Always a clean rebuild. Adding to an existing profile would collide
            // on train.numero, and a run that half-succeeded would leave a fleet
            // whose size nobody could state -- which is the one thing a
            // measurement cannot afford.
            flotteCharge.supprimer();
            flotteCharge.creer(trains, depart, etalement);

            LocalDate aujourdhui = LocalDate.now(ZONE_RESEAU);
            int courses = generateurCourses.genererPour(aujourdhui);
            log.info("Profil de charge prêt : {} course(s) générée(s) pour le {}, "
                            + "dont {} issues du profil.",
                    courses, aujourdhui, trains);
            log.info("Lancez le simulateur avec TRINO_SIM_HEURE_DEBUT juste avant {} "
                    + "pour que la flotte parte.", depart);
        } finally {
            // One-shot tool: the context would otherwise sit there holding the
            // scheduler, and -- unless server.port=0 was passed -- a port.
            // In the finally block so a failed run still exits rather than
            // leaving a half-built profile behind a live process.
            System.exit(SpringApplication.exit(contexte, () -> 0));
        }
    }

    private int entier(ApplicationArguments args, String nom, int defaut) {
        String valeur = premiere(args, nom);
        if (valeur == null) {
            return defaut;
        }
        try {
            return Integer.parseInt(valeur.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + nom + " doit être un entier, reçu « " + valeur + " ».");
        }
    }

    private LocalTime heure(ApplicationArguments args, String nom, String defaut) {
        String valeur = premiere(args, nom);
        try {
            return LocalTime.parse(valeur == null ? defaut : valeur.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("--" + nom + " doit être une heure HH:mm, reçu « " + valeur + " ».");
        }
    }

    /** {@code --trains} with no value is a usage error, not a request for the default. */
    private String premiere(ApplicationArguments args, String nom) {
        if (!args.containsOption(nom)) {
            return null;
        }
        var valeurs = args.getOptionValues(nom);
        if (valeurs == null || valeurs.isEmpty()) {
            throw new IllegalArgumentException("--" + nom + " attend une valeur.");
        }
        return valeurs.get(0);
    }
}
