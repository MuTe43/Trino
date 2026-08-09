package tn.sncft.trino.circulation.backfill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Synthesises finished service dates so the dashboard has history to chart.
 *
 * <p>Running the simulator longer does not produce this. {@code
 * GenerateurCourses} materialises one service date and the simulator replays
 * that date; at x60 you get one day rendered quickly, every course at
 * TERMINUS_ATTEINT, and a flat chart. Several days have to be written directly.
 *
 * <p>Profile-gated so it can never fire during a normal API run. Invoked by
 * {@code scripts/backfill.sh}:
 *
 * <pre>
 * ./mvnw -pl api spring-boot:run -Dspring-boot.run.profiles=backfill
 * </pre>
 *
 * <p>A range is used rather than a single day because a one-day punctuality
 * figure carries about ±5 points of run-to-run noise (measured in phase 2), so
 * the headline number would move on every demo reset. Fourteen days behind a
 * seven-day window is stable.
 */
@Component
@Profile("backfill")
public class BackfillHistorique implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BackfillHistorique.class);

    /** Timetable dates are local wall-clock, same zone as GenerateurCourses. */
    private static final ZoneId ZONE_RESEAU = ZoneId.of("Africa/Tunis");

    private static final int JOURS_PAR_DEFAUT = 14;

    private final HistorisateurJournee historisateur;
    private final ConfigurableApplicationContext contexte;

    public BackfillHistorique(HistorisateurJournee historisateur, ConfigurableApplicationContext contexte) {
        this.historisateur = historisateur;
        this.contexte = contexte;
    }

    @Override
    public void run(ApplicationArguments args) {
        int jours = lireJours(args);
        LocalDate aujourdhui = LocalDate.now(ZONE_RESEAU);

        int total = 0;
        // Strictly before today, oldest first. Today is the live day: the
        // simulator owns it, and stamping it as finished would contradict the
        // feed and wipe whatever the delay engine has recorded so far.
        for (int recul = jours; recul >= 1; recul--) {
            LocalDate date = aujourdhui.minusDays(recul);
            total += historisateur.historiser(date);
        }
        log.info("Backfill terminé : {} courses sur {} jours, jusqu'au {}.",
                total, jours, aujourdhui.minusDays(1));

        // One-shot tool: the context would otherwise sit there holding the
        // scheduler and, unless web-application-type=none was passed, a port.
        System.exit(SpringApplication.exit(contexte, () -> 0));
    }

    /** {@code --jours=30} overrides the default; anything unparseable is refused. */
    private int lireJours(ApplicationArguments args) {
        if (!args.containsOption("jours")) {
            return JOURS_PAR_DEFAUT;
        }
        String valeur = args.getOptionValues("jours").get(0);
        int jours = Integer.parseInt(valeur);
        if (jours < 1) {
            throw new IllegalArgumentException("--jours doit être au moins 1, reçu " + jours);
        }
        return jours;
    }
}
