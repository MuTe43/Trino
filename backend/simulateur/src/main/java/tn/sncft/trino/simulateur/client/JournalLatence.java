package tn.sncft.trino.simulateur.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ingest latency as the position producer sees it: the whole round trip of
 * {@code POST /api/v1/ingest/positions}, including the delay engine and the SSE
 * fan-out, which {@code PublicationApresCommit} runs on the request thread.
 *
 * <p>Measured here rather than server-side on purpose. The API's actuator
 * reports only COUNT, TOTAL_TIME and MAX for a timer, so percentiles would mean
 * adding a Prometheus registry for one number — and the producer's view is the
 * one that matters anyway. Real AVL hardware experiences this figure; a
 * server-side histogram excludes the serialisation and the network that the
 * hardware pays for.
 *
 * <p>Fixed-size reservoir, oldest sample overwritten. A load run posts a batch
 * every five seconds for a few minutes, so this holds the whole run several
 * times over; it is bounded so a producer left up overnight cannot grow it.
 * Percentiles are computed by sorting a copy, which at this size costs less than
 * the HTTP call being measured.
 */
@Component
public class JournalLatence {

    private static final Logger log = LoggerFactory.getLogger(JournalLatence.class);

    /** Roughly eight hours of five-second ticks. */
    private static final int CAPACITE = 8192;

    private final long[] echantillons = new long[CAPACITE];
    private final AtomicLong total = new AtomicLong();

    /**
     * Records one round trip.
     *
     * <p>Called from the single scheduler thread that drives the tick, so the
     * array write needs no lock. The counter is atomic only so {@link #resume()}
     * can be called from elsewhere without reading a torn value.
     */
    public void enregistrer(long millisecondes) {
        long rang = total.getAndIncrement();
        echantillons[(int) (rang % CAPACITE)] = millisecondes;
    }

    /**
     * One line: sample count, p50, p95, p99 and max, in milliseconds.
     *
     * @return the summary, or a notice when nothing has been recorded yet
     */
    public String resume() {
        long compte = total.get();
        if (compte == 0) {
            return "aucune mesure";
        }
        int taille = (int) Math.min(compte, CAPACITE);
        long[] tries = Arrays.copyOf(echantillons, taille);
        Arrays.sort(tries);
        return String.format("n=%d p50=%dms p95=%dms p99=%dms max=%dms",
                compte,
                centile(tries, 0.50),
                centile(tries, 0.95),
                centile(tries, 0.99),
                tries[taille - 1]);
    }

    /** Logs {@link #resume()}. Called by the tick, not by a scheduler of its own. */
    public void journaliser() {
        log.info("Latence d'ingestion : {}", resume());
    }

    /**
     * Nearest-rank percentile on an already-sorted array.
     *
     * <p>Nearest-rank rather than interpolated: every sample is a latency that
     * actually happened, and at these sample counts an interpolated p95 differs
     * by well under the millisecond the clock resolves anyway.
     */
    private static long centile(long[] tries, double part) {
        int rang = (int) Math.ceil(part * tries.length) - 1;
        return tries[Math.clamp(rang, 0, tries.length - 1)];
    }
}
