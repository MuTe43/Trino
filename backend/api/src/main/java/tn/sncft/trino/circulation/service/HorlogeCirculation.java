package tn.sncft.trino.circulation.service;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The clock the delay engine reasons on.
 *
 * <p>This exists because the position feed carries its own clock. The simulator
 * runs an accelerated one ({@code maintenantSimule}), so at acceleration 20 a
 * ping is stamped hours away from wall-clock now. Comparing
 * {@code derniere_position_at} or {@code depart_theorique} against
 * {@code OffsetDateTime.now()} would then mark the entire day's traffic as
 * silent or as never-departed. Real AVL hardware reports real time, so at
 * acceleration 1 this degrades to exactly the system clock.
 *
 * <p>The rule is: the feed sets the time, real seconds carry it forward between
 * pings. That second half matters -- anchoring on the last ping alone would
 * freeze the clock when the feed dies, and a frozen clock can never conclude
 * that the feed has been silent for 90 seconds.
 */
@Component
public class HorlogeCirculation {

    private final AtomicReference<Ancre> ancre = new AtomicReference<>();

    /** Advances the clock from an observed feed timestamp. Monotonic. */
    public void observer(OffsetDateTime horodatageFeed) {
        ancre.updateAndGet(actuel ->
                actuel == null || horodatageFeed.isAfter(actuel.feed())
                        ? new Ancre(horodatageFeed, Instant.now())
                        : actuel);
    }

    /** Network time now. Falls back to the system clock until a first ping. */
    public OffsetDateTime maintenant() {
        Ancre actuel = ancre.get();
        if (actuel == null) {
            return OffsetDateTime.now(ZoneOffset.UTC);
        }
        return actuel.feed().plus(Duration.between(actuel.reel(), Instant.now()));
    }

    /** Both halves in one reference so a reader never mixes a new feed time with an old anchor. */
    private record Ancre(OffsetDateTime feed, Instant reel) {
    }
}
