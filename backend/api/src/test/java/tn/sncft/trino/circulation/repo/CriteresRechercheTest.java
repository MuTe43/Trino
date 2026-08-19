package tn.sncft.trino.circulation.repo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tn.sncft.trino.circulation.domaine.Course;
import tn.sncft.trino.circulation.domaine.StatutCourse;
import tn.sncft.trino.support.BaseDeDonneesTest;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The four search criteria §4.9 asked for and phase 9 added, executed against a
 * real PostgreSQL.
 *
 * <p>This exists because of a specific failure and not for coverage. The first
 * cut bound {@code departMin}/{@code departMax} as nullable parameters guarded
 * by {@code :param is null}, which Postgres cannot infer a type for: every call
 * to {@code /recherche} answered 500 {@code could not determine data type of
 * parameter $23}, including calls passing no time window at all. Phase 6 hit the
 * identical error on {@code $7} in the référentiel filters, which is what
 * {@code FiltresReferentielTest} exists for — the same mistake twice, in two
 * years' worth of phases, both times invisible to the compiler and to every test
 * that used a mock.
 *
 * <p>The assertions are therefore about the query <em>running</em> and about the
 * filters narrowing rather than about specific rows. Course rows are written by
 * {@code GenerateurCourses} at application start, which a {@code @DataJpaTest}
 * does not run, so the number of rows present depends on whether the API has
 * been up — and a test that asserted a count would pass or fail on that.
 *
 * <p>Required, not optional, like every other DB-backed test since phase 6 —
 * see {@link BaseDeDonneesTest}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisabledIfSystemProperty(named = "trino.tests.sansDb", matches = "true",
        disabledReason = "opt-out explicite : base de données non requise")
class CriteresRechercheTest {

    private static final ZoneId ZONE_RESEAU = ZoneId.of("Africa/Tunis");

    @DynamicPropertySource
    static void source(DynamicPropertyRegistry registre) {
        registre.add("spring.datasource.url", () -> BaseDeDonneesTest.URL);
        registre.add("spring.datasource.username", () -> BaseDeDonneesTest.UTILISATEUR);
        registre.add("spring.datasource.password", () -> BaseDeDonneesTest.MOT_DE_PASSE);
    }

    @Autowired
    private CourseRepository courseRepository;

    /**
     * The most recent service date that actually has courses.
     *
     * Not simply {@code LocalDate.now()}: GenerateurCourses materialises the day
     * at API startup and at 03:00, so between midnight and whichever comes first
     * today's table is empty and every assertion here would fail on a
     * precondition rather than on the behaviour under test. Caught exactly that
     * way, at 00:15, by a date rollover mid-verification.
     *
     * Walking back is safe for these tests: they assert on how criteria narrow
     * and on how a wall-clock window is resolved, neither of which depends on the
     * date being today.
     */
    private LocalDate jour;

    @BeforeEach
    void choisirJour() {
        LocalDate candidat = LocalDate.now(ZONE_RESEAU);
        for (int recul = 0; recul <= JOURS_RECHERCHES; recul++) {
            if (!courseRepository.findClesByDateService(candidat.minusDays(recul)).isEmpty()) {
                jour = candidat.minusDays(recul);
                return;
            }
        }
        BaseDeDonneesTest.exiger(false,
                "aucune course sur les " + JOURS_RECHERCHES + " derniers jours : "
                        + "lancez scripts/backfill.sh ou demarrez l'API");
    }

    /** Far enough back to clear a rollover and a weekend, short enough to stay cheap. */
    private static final int JOURS_RECHERCHES = 20;

    @Test
    @DisplayName("aucun critère : la requête s'exécute et la fenêtre par défaut couvre la journée")
    void sansCritereLaRequeteSExecute() {
        assertNotNull(rechercher(null, null, null, LocalTime.MIN, LocalTime.MAX));
    }

    @Test
    @DisplayName("chaque critère ajouté séparément s'exécute et ne peut qu'élargir moins que le total")
    void chaqueCritereSExecuteEtRestreint() {
        long total = rechercher(null, null, null, LocalTime.MIN, LocalTime.MAX).getTotalElements();

        // Sousse and Gabès are both in the V2 seed, so these patterns match
        // something real rather than exercising an always-empty branch.
        long parRegion = rechercher(null, "%sousse%", null, LocalTime.MIN, LocalTime.MAX).getTotalElements();
        long parDestination = rechercher(null, null, "%gab%", LocalTime.MIN, LocalTime.MAX).getTotalElements();
        long parFenetre = rechercher(null, null, null,
                LocalTime.of(6, 0), LocalTime.of(9, 0)).getTotalElements();
        long parTexte = rechercher("%tunis%", null, null, LocalTime.MIN, LocalTime.MAX).getTotalElements();

        assertTrue(parRegion <= total, "région : " + parRegion + " > " + total);
        assertTrue(parDestination <= total, "destination : " + parDestination + " > " + total);
        assertTrue(parFenetre <= total, "fenêtre horaire : " + parFenetre + " > " + total);
        assertTrue(parTexte <= total, "texte libre : " + parTexte + " > " + total);
    }

    @Test
    @DisplayName("la fenêtre horaire est résolue en Africa/Tunis, pas en UTC")
    void fenetreResolueEnHeureLocale() {
        // The first version of this test compared two calls built from the same
        // helper and could not fail -- exactly the mistake phase-9.md opens by
        // warning about. This one is falsifiable: it pins the window to the
        // wall-clock minute of a real departure and asserts that the SAME numbers
        // read as UTC find nothing.
        //
        // Africa/Tunis is UTC+1, so a window the caller means as 05:30 local is
        // 04:30 UTC. Resolving it in the wrong zone shifts every result by an
        // hour, silently, and looks like "the search just found fewer trains".
        OffsetDateTime premierDepart = courseRepository.findClesByDateService(jour).stream()
                .map(CourseRepository.CleCourse::getDepartTheorique)
                .min(OffsetDateTime::compareTo)
                .orElse(null);
        assertNotNull(premierDepart, "la journee " + jour + " doit avoir au moins un depart");

        LocalTime heureLocale = premierDepart.atZoneSameInstant(ZONE_RESEAU).toLocalTime();

        Set<Long> enLocal = idsPourFenetre(heureLocale, ZONE_RESEAU);
        Set<Long> enUtc = idsPourFenetre(heureLocale, ZoneOffset.UTC);

        // Identity, not counts. The first attempt asserted the UTC window found
        // nothing, and it found one -- because a different course happens to
        // depart exactly an hour after the earliest, which is what the UTC
        // reading of these same digits names. The claim worth pinning is that
        // the two windows designate different minutes and therefore cannot
        // return the same run: a course has exactly one departTheorique.
        assertFalse(enLocal.isEmpty(),
                "la borne locale " + heureLocale + " doit retrouver le depart qu'elle nomme");
        assertTrue(Collections.disjoint(enLocal, enUtc),
                "la fenetre lue en UTC designe " + heureLocale.plusHours(1)
                        + " locale et ne peut pas partager de course avec " + heureLocale
                        + " : " + enLocal + " vs " + enUtc);
    }

    /** Course ids departing exactly at {@code heure}, read in the given zone. */
    private Set<Long> idsPourFenetre(LocalTime heure, java.time.ZoneId zone) {
        OffsetDateTime borne = jour.atTime(heure).atZone(zone).toOffsetDateTime();
        return courseRepository.rechercher(
                        jour, null, null, List.of(StatutCourse.values()), null, null, null, null,
                        borne, borne, PageRequest.of(0, 50))
                .getContent().stream()
                .map(Course::getId)
                .collect(Collectors.toSet());
    }

    private Page<Course> rechercher(String q, String region, String destination,
                                    LocalTime debut, LocalTime fin) {
        return courseRepository.rechercher(
                jour, null, null, List.of(StatutCourse.values()), null,
                q, region, destination,
                instant(debut), instant(fin),
                PageRequest.of(0, 20));
    }

    private OffsetDateTime instant(LocalTime heure) {
        return jour.atTime(heure).atZone(ZONE_RESEAU).toOffsetDateTime();
    }
}
