package tn.sncft.trino.analytique.repository;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tn.sncft.trino.analytique.dto.CaseHeatmapDTO;
import tn.sncft.trino.analytique.dto.Granularite;
import tn.sncft.trino.analytique.dto.PointPonctualiteDTO;
import tn.sncft.trino.analytique.dto.RetardParLigneDTO;
import tn.sncft.trino.support.BaseDeDonneesTest;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dashboard aggregates, against the local dev database.
 *
 * <p><b>Fails</b> rather than skips when no database is reachable, like
 * {@code CoherenceSeedTest} -- see {@link BaseDeDonneesTest}. A wrong
 * {@code TRINO_DB_URL} used to produce a silent skip that was indistinguishable
 * from a pass, which meant none of this SQL was ever exercised on a green run.
 *
 * <p>Assertions are on properties rather than on exact figures. The backfill is
 * deterministic, but pinning it to a specific punctuality percentage would make
 * a timetable change look like an analytics bug.
 */
class AnalytiqueRepositoryTest {

    private static final String URL = BaseDeDonneesTest.URL;
    private static final String UTILISATEUR = BaseDeDonneesTest.UTILISATEUR;
    private static final String MOT_DE_PASSE = BaseDeDonneesTest.MOT_DE_PASSE;

    private static AnalytiqueRepository depot;

    /** A date the backfill has written, and the window the dashboard defaults to. */
    private static LocalDate uneDatePassee;
    private static LocalDate du;
    private static LocalDate au;

    @BeforeAll
    static void preparer() {
        // Reachability and content are reported apart: an empty database and an
        // unreachable one need different fixes, and "run scripts/backfill.sh"
        // is not something a connection error would ever tell you.
        try (Connection connexion = BaseDeDonneesTest.ouvrir();
             Statement statement = connexion.createStatement();
             ResultSet resultat = statement.executeQuery(
                     "select max(date_service) from course where statut = 'TERMINUS_ATTEINT'")) {
            boolean journeeTerminee = resultat.next() && resultat.getDate(1) != null;
            BaseDeDonneesTest.exiger(journeeTerminee,
                    "aucune journée terminée en base : lancez scripts/backfill.sh");
            uneDatePassee = resultat.getDate(1).toLocalDate();
            au = uneDatePassee;
            du = au.minusDays(6);
        } catch (SQLException e) {
            BaseDeDonneesTest.exiger(false, "base de développement injoignable (" + e.getMessage() + ")");
            return;
        }
        DriverManagerDataSource source = new DriverManagerDataSource(URL, UTILISATEUR, MOT_DE_PASSE);
        depot = new AnalytiqueRepository(new JdbcTemplate(source));
    }

    @Test
    @DisplayName("le taux de ponctualité du jour reste entre 0 et 1")
    void leTauxResteBorne() {
        AnalytiqueRepository.CompteursPonctualite compteurs = depot.ponctualiteDuJour(uneDatePassee);

        assertTrue(compteurs.mesures() > 0, "aucun passage mesuré sur " + uneDatePassee);
        assertTrue(compteurs.ponctuels() <= compteurs.mesures());
        assertTrue(compteurs.taux() >= 0 && compteurs.taux() <= 1, "taux hors bornes : " + compteurs.taux());
    }

    /**
     * The rule that keeps a dashboard honest mid-morning: a stop the train has
     * not reached yet carries {@code retard_min = 0}, so counting it would
     * score the untravelled rest of the day as on time.
     */
    @Test
    @DisplayName("la ponctualité ne compte que les arrêts réellement desservis")
    void laPonctualiteIgnoreLesArretsNonAtteints() {
        // Measured on a day the backfill has written rather than on today: the
        // assertion holds for any date, and today is empty on a machine where
        // the API has never run -- which used to skip this test silently, the
        // exact vacuous pass this phase set out to remove.
        DriverManagerDataSource source = new DriverManagerDataSource(URL, UTILISATEUR, MOT_DE_PASSE);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        Long tousLesPassages = jdbc.queryForObject("""
                select count(*) from passage_gare pg
                  join course c on c.id = pg.course_id
                where c.date_service = ?
                """, Long.class, uneDatePassee);
        Long passagesAtteints = jdbc.queryForObject("""
                select count(*) from passage_gare pg
                  join course c on c.id = pg.course_id
                where c.date_service = ? and pg.arrivee_reelle is not null
                """, Long.class, uneDatePassee);

        assertTrue(tousLesPassages != null && tousLesPassages > 0, "aucun passage le " + uneDatePassee);
        assertEquals(passagesAtteints, depot.ponctualiteDuJour(uneDatePassee).mesures());
    }

    @Test
    @DisplayName("les compteurs du jour sont cohérents entre eux")
    void lesCompteursSontCoherents() {
        AnalytiqueRepository.CompteursJour compteurs = depot.compteursDuJour(uneDatePassee);

        assertTrue(compteurs.trains() > 0, "aucune course le " + uneDatePassee);
        assertTrue(compteurs.retards() <= compteurs.trains());
        assertTrue(compteurs.retardMoyenMin() >= 0);
        // Estimated from train capacity on delayed courses, so it moves with
        // the delayed count and must be zero exactly when that is.
        assertEquals(compteurs.retards() == 0, compteurs.voyageursImpactes() == 0);
    }

    @Test
    @DisplayName("les retards par ligne couvrent chaque ligne ayant circulé")
    void lesRetardsParLigneCouvrentLeReseau() {
        List<RetardParLigneDTO> lignes = depot.retardsParLigne(uneDatePassee);

        assertFalse(lignes.isEmpty());
        for (RetardParLigneDTO ligne : lignes) {
            assertTrue(ligne.coursesEnRetard() <= ligne.courses(),
                    "plus de courses en retard que de courses sur " + ligne.ligneNom());
            assertTrue(ligne.retardMaxMin() >= 0);
        }
    }

    @Test
    @DisplayName("la courbe de ponctualité rend un point par jour de la plage")
    void laCourbeRendUnPointParJour() {
        List<PointPonctualiteDTO> points = depot.ponctualite(du, au, Granularite.JOUR);

        assertFalse(points.isEmpty());
        assertTrue(points.size() <= 7, "plus de points que de jours dans la plage");
        for (PointPonctualiteDTO point : points) {
            assertTrue(!point.periode().isBefore(du) && !point.periode().isAfter(au),
                    "point hors plage : " + point.periode());
            assertTrue(point.tauxPonctualite() >= 0 && point.tauxPonctualite() <= 1);
            assertTrue(point.passagesPonctuels() <= point.passages());
        }
    }

    @Test
    @DisplayName("la granularité mois regroupe la plage en un point")
    void laGranulariteMoisRegroupe() {
        List<PointPonctualiteDTO> parMois = depot.ponctualite(du, au, Granularite.MOIS);

        assertTrue(parMois.size() <= 2, "une fenêtre de 7 jours couvre au plus deux mois");
        for (PointPonctualiteDTO point : parMois) {
            assertEquals(1, point.periode().getDayOfMonth(), "un bucket mensuel commence le 1er");
        }
    }

    @Test
    @DisplayName("les heures de la heatmap sont exprimées en Africa/Tunis")
    void lesHeuresSontLocales() {
        List<CaseHeatmapDTO> cases = depot.heatmap(du, au);

        assertFalse(cases.isEmpty());
        for (CaseHeatmapDTO cellule : cases) {
            assertTrue(cellule.heure() >= 0 && cellule.heure() <= 23, "heure hors bornes : " + cellule.heure());
            assertTrue(cellule.passages() > 0);
        }

        // Not "the first hour looks plausible" -- long-distance runs leaving in
        // the evening genuinely arrive after midnight, so hour 0 is real data.
        // What is asserted is the shift itself: Africa/Tunis is UTC+1 with no
        // DST, so each local bucket must hold exactly what the bucket an hour
        // earlier holds in UTC. Dropping `at time zone` breaks this outright.
        Map<Integer, Long> parHeureLocale = new HashMap<>();
        for (CaseHeatmapDTO cellule : cases) {
            parHeureLocale.merge(cellule.heure(), cellule.passages(), Long::sum);
        }

        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(URL, UTILISATEUR, MOT_DE_PASSE));
        // `at time zone 'UTC'` is not redundant. extract(hour from timestamptz)
        // resolves against the *session* TimeZone, which the JDBC driver sets
        // from the JVM default -- Europe/London on this machine, +01:00 in
        // August, the same offset as Africa/Tunis. Without pinning it, this
        // control query returns the local hours it is supposed to be compared
        // against and the test passes whether or not the repository converts.
        // It is also why the repository query names its zone explicitly rather
        // than trusting the session.
        Map<Integer, Long> parHeureUtc = new HashMap<>();
        jdbc.query("""
                select extract(hour from pg.arrivee_theorique at time zone 'UTC')::int as heure,
                       count(*) as passages
                from passage_gare pg
                  join course c on c.id = pg.course_id
                where c.date_service between ? and ?
                  and pg.arrivee_theorique is not null
                  and pg.arrivee_reelle is not null
                group by heure
                """, (rs, numeroLigne) -> parHeureUtc.put(rs.getInt("heure"), rs.getLong("passages")), du, au);

        for (Map.Entry<Integer, Long> entree : parHeureLocale.entrySet()) {
            int heureUtcAttendue = Math.floorMod(entree.getKey() - 1, 24);
            assertEquals(entree.getValue(), parHeureUtc.get(heureUtcAttendue),
                    "l'heure locale " + entree.getKey() + " ne correspond pas à l'heure UTC "
                            + heureUtcAttendue + " : conversion de fuseau absente ?");
        }
    }

    // The two incident queries added in phase 6 are covered by
    // IncidentRepositoryTest, which seeds its own rows: asserted here they would
    // have been vacuously true on a freshly migrated database, where the
    // incident table is empty -- the exact silent-pass this phase set out to
    // remove.
}
