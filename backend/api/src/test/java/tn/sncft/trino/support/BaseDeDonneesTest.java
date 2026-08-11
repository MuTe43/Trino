package tn.sncft.trino.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assumptions.abort;

/**
 * Connection settings and the fail-closed precondition shared by the tests that
 * run against the real development database.
 *
 * <p>These tests used to skip themselves whenever Postgres was unreachable, so
 * {@code mvnw test} on a machine with a wrong {@code TRINO_DB_URL} reported
 * green having exercised none of the seed or dashboard SQL. A skip and a pass
 * are indistinguishable in a build summary, and the checks that matter most --
 * the ones guarding against plausible-looking wrong numbers -- were exactly the
 * ones being skipped.
 *
 * <p>The default is now inverted: <b>the database is required</b>. A machine
 * that genuinely has none opts out explicitly with {@code -Dtrino.tests.sansDb=true}
 * (or {@code TRINO_TESTS_SANS_DB=1}), which restores the skip. Silence is no
 * longer a way to pass.
 */
public final class BaseDeDonneesTest {

    public static final String URL =
            System.getenv().getOrDefault("TRINO_DB_URL", "jdbc:postgresql://localhost:5432/trino");
    public static final String UTILISATEUR = System.getenv().getOrDefault("TRINO_DB_USER", "trino");
    public static final String MOT_DE_PASSE = System.getenv().getOrDefault("TRINO_DB_PASSWORD", "trino");

    private BaseDeDonneesTest() {
    }

    /** The explicit opt-out. Anything else -- unset, empty, "0" -- means the database is required. */
    public static boolean sansBase() {
        return Boolean.parseBoolean(System.getProperty("trino.tests.sansDb"))
                || "1".equals(System.getenv("TRINO_TESTS_SANS_DB"));
    }

    public static Connection ouvrir() throws SQLException {
        return DriverManager.getConnection(URL, UTILISATEUR, MOT_DE_PASSE);
    }

    /**
     * Asserts a precondition these tests cannot run without.
     *
     * <p>Fails the class rather than skipping it, unless the opt-out is set. The
     * message names the URL that was tried, because the usual cause is a
     * {@code TRINO_DB_URL} pointing at another Postgres on the same host -- which
     * connects fine and then has none of the tables.
     */
    public static void exiger(boolean satisfait, String probleme) {
        if (satisfait) {
            return;
        }
        if (sansBase()) {
            abort(probleme + " -- ignoré (trino.tests.sansDb)");
        }
        throw new IllegalStateException(probleme
                + " -- URL essayée : " + URL
                + ". Renseignez TRINO_DB_URL, ou passez -Dtrino.tests.sansDb=true pour ignorer"
                + " explicitement ces contrôles.");
    }
}
