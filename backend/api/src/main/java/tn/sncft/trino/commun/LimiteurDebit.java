package tn.sncft.trino.commun;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A fixed-window counter per key. In memory, no library, as api-contract.md
 * says.
 *
 * <p>Fixed window rather than a sliding one or a token bucket: the thing being
 * protected is an unauthenticated endpoint that sends mail, and the difference
 * between 10 per minute and up to 20 across a window boundary does not change
 * whether that endpoint can be used to send someone a thousand emails. The
 * simpler structure has no timer, no eviction thread and nothing to get wrong.
 *
 * <p>Windows are dropped lazily, when their key is next touched, plus a sweep
 * when the map grows past {@link #SEUIL_NETTOYAGE}. An unbounded map keyed on a
 * caller-controlled value is a memory leak that a caller chooses the size of.
 *
 * <p>Not a {@code @Component}: {@link ConfigurationWeb} owns the instance and
 * publishes it. A {@code WebMvcConfigurer} is loaded by every {@code @WebMvcTest}
 * slice in the suite, and a plain component is not -- so a scanned bean here
 * would fail the context of tests that have nothing to do with rate limiting.
 */
public class LimiteurDebit {

    private static final int SEUIL_NETTOYAGE = 10_000;

    private final Map<String, Fenetre> fenetres = new ConcurrentHashMap<>();

    /**
     * Consumes one unit for {@code cle}, and says whether it was within budget.
     *
     * @return true when the call may proceed
     */
    public boolean autoriser(String cle, int maximum, Duration duree) {
        Instant maintenant = Instant.now();
        if (fenetres.size() > SEUIL_NETTOYAGE) {
            fenetres.values().removeIf(fenetre -> fenetre.expiree(maintenant));
        }
        Fenetre fenetre = fenetres.compute(cle, (ignore, actuelle) ->
                actuelle == null || actuelle.expiree(maintenant)
                        ? new Fenetre(maintenant.plus(duree))
                        : actuelle);
        return fenetre.compteur().incrementAndGet() <= maximum;
    }

    /** Visible for tests: forget everything, so one test's calls cannot exhaust another's budget. */
    public void reinitialiser() {
        fenetres.clear();
    }

    private record Fenetre(Instant finAt, AtomicInteger compteur) {

        Fenetre(Instant finAt) {
            this(finAt, new AtomicInteger());
        }

        boolean expiree(Instant maintenant) {
            return !maintenant.isBefore(finAt);
        }
    }
}
