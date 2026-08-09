package tn.sncft.trino.diffusion;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * One client's connection and the set of channels it asked for.
 *
 * <p>Before phase 5 a subscriber was just an {@code SseEmitter} pinned to a
 * single channel, so a client watching five lignes held five connections --
 * against a browser budget of about six per origin over HTTP/1.1, shared with
 * every REST call to the same origin. This type is what lets one connection
 * carry several channels.
 *
 * <p>Deliberately not a record and deliberately without {@code equals}: the hub
 * stores the same instance in several channel buckets and de-duplicates by
 * identity when a delta concerns more than one of them.
 */
final class AbonnementSse {

    private final SseEmitter emitter;
    private final Set<String> canaux;
    private final boolean enveloppe;

    AbonnementSse(SseEmitter emitter, Set<String> canaux, boolean enveloppe) {
        this.emitter = emitter;
        this.canaux = Set.copyOf(canaux);
        this.enveloppe = enveloppe;
    }

    SseEmitter emitter() {
        return emitter;
    }

    Set<String> canaux() {
        return canaux;
    }

    /**
     * Multiplexed subscribers get {@link EnveloppeSse} tagged with every one of
     * this subscription's channels that the delta concerns; single-channel ones
     * get the bare delta, which is the shape api-contract.md documents for the
     * per-ligne and per-gare endpoints.
     */
    Object charge(Collection<String> canauxConcernes, Object donnees) {
        return enveloppe ? new EnveloppeSse(List.copyOf(canauxConcernes), donnees) : donnees;
    }
}
