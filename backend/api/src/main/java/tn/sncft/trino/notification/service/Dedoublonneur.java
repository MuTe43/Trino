package tn.sncft.trino.notification.service;

import org.springframework.stereotype.Component;
import tn.sncft.trino.circulation.service.HorlogeCirculation;
import tn.sncft.trino.notification.domaine.Evenement;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One notification per (abonnement, evenement, course) per 30 simulated minutes.
 *
 * <p>Without this the phase does not survive its own demo. A course crossing a
 * delay threshold stays above it, and the engine re-evaluates on every ping, so
 * a subscriber would receive one message per ping for the rest of the run. At
 * the x20 replay the simulator normally runs at, that is hundreds of emails in a
 * minute -- discovered, if not here, then in front of the room.
 *
 * <p><b>Simulated</b> minutes, from {@link HorlogeCirculation}, not wall-clock
 * ones. The window has to mean the same thing in network time as the delay it
 * describes: judged on the wall clock, 30 minutes of replay at x20 is 90 real
 * seconds and the guard would let through twenty times too many. At acceleration
 * 1 the two clocks are the same thing, so nothing about real hardware changes.
 *
 * <p>The state is in memory, like {@code EtatCirculationStore} and for the same
 * reason: it is hot, per-process, and worthless after a restart -- the worst a
 * restart can cause is one duplicate notification per subscriber. Entries are
 * evicted when they fall a full window behind the clock, so the map tracks
 * what is currently late rather than everything that ever was.
 */
@Component
public class Dedoublonneur {

    /** The window the phase file fixes. Simulated minutes. */
    static final Duration FENETRE = Duration.ofMinutes(30);

    private final HorlogeCirculation horloge;

    private final Map<Cle, OffsetDateTime> dernieresEmissions = new ConcurrentHashMap<>();

    public Dedoublonneur(HorlogeCirculation horloge) {
        this.horloge = horloge;
    }

    /**
     * True when this notification may be emitted, and records it if so.
     *
     * <p>{@code sujet} is whatever the event is <em>about</em>: the course for a
     * delay or a cancellation, the incident for a declaration or a resolution.
     * The two spaces cannot collide because {@link Evenement} is part of the key
     * and no event value is used for both.
     *
     * <p>Test-and-record in one atomic step: two pings for the same course can
     * land on two dispatcher threads at once, and a separate "may I?" followed
     * by "I did" would let both through.
     */
    public boolean autoriser(Long abonnementId, Evenement evenement, Long sujet) {
        OffsetDateTime maintenant = horloge.maintenant();
        Cle cle = new Cle(abonnementId, evenement, sujet);
        boolean[] autorise = {false};

        dernieresEmissions.compute(cle, (ignore, derniere) -> {
            if (derniere == null || maintenant.isAfter(derniere.plus(FENETRE))) {
                autorise[0] = true;
                return maintenant;
            }
            return derniere;
        });

        if (autorise[0]) {
            evincerPerimes(maintenant);
        }
        return autorise[0];
    }

    /**
     * Drops keys older than one full window. Called only on the rarer path (an
     * emission actually going out), so the common case -- a ping suppressed by
     * the guard -- stays a single map operation.
     */
    private void evincerPerimes(OffsetDateTime maintenant) {
        OffsetDateTime limite = maintenant.minus(FENETRE);
        dernieresEmissions.entrySet().removeIf(entree -> entree.getValue().isBefore(limite));
    }

    /** Visible for tests: how many windows are currently being tracked. */
    int taille() {
        return dernieresEmissions.size();
    }

    /**
     * {@code sujet} is nullable -- a course event on a course the engine could
     * not resolve -- so this is a record rather than a string key, and null is a
     * legitimate part of the identity rather than the literal "null".
     */
    private record Cle(Long abonnementId, Evenement evenement, Long sujet) {
    }
}
