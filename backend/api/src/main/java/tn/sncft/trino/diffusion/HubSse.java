package tn.sncft.trino.diffusion;

import org.apache.catalina.connector.ClientAbortException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Fan-out for the live channels. SSE rather than WebSocket because traffic is
 * server to client only (decision 3).
 *
 * <p>Channels are scoped: {@code ligne:{id}} and {@code gare:{id}}, never a
 * global one. A station board subscribes to its own gare and receives nothing
 * about the rest of the network.
 *
 * <p>A subscriber may hold several channels on one connection ({@link
 * #abonnerMultiplex}). That is a transport change, not a scoping change: the
 * channel set is still whatever the client explicitly asked for, so invariant 5
 * holds -- nobody is ever handed the whole network.
 */
@Component
public class HubSse {

    private static final Logger log = LoggerFactory.getLogger(HubSse.class);

    /**
     * Never time out. Reconnection is the client's job.
     *
     * <p>Note that no frame carries an {@code id}, so there is no replay and a
     * reconnecting client cannot resume with {@code Last-Event-ID}: it refetches
     * its snapshot over REST instead. Deltas emitted while a client is
     * reconnecting are lost, by design rather than by oversight.
     */
    private static final long SANS_EXPIRATION = 0L;

    private final Map<String, List<AbonnementSse>> abonnes = new ConcurrentHashMap<>();

    /**
     * Every live subscription, once each regardless of how many channels it
     * holds. The heartbeat walks this instead of the channel map: a client on
     * five channels needs one comment frame every 15 s, not five.
     */
    private final Set<AbonnementSse> connexions = ConcurrentHashMap.newKeySet();

    public static String canalLigne(Long ligneId) {
        return "ligne:" + ligneId;
    }

    public static String canalGare(Long gareId) {
        return "gare:" + gareId;
    }

    /**
     * One channel, one connection. Kept for the station board, which watches
     * exactly one gare and has no reason to carry the multiplexing machinery.
     * Its frames stay the bare payload documented in api-contract.md.
     */
    public SseEmitter abonner(String canal) {
        return enregistrer(Set.of(canal), false);
    }

    /**
     * Several channels over one connection, each frame tagged with the channel
     * it came from. This is what keeps the map from opening one socket per
     * ligne and exhausting the browser's per-origin connection budget.
     */
    public SseEmitter abonnerMultiplex(Set<String> canaux) {
        return enregistrer(canaux, true);
    }

    /**
     * Registers a subscriber. The three removal callbacks are written before
     * anything else on purpose: an emitter that outlives its client is the
     * classic leak in this design, and all three of completion, timeout and
     * error have to unregister or the map grows for the life of the process.
     *
     * <p>{@code onError} calls {@link #detacher}, not just {@link #retirer}:
     * per the Servlet async contract, if {@code onError} does not complete
     * the async context itself, the container completes the dispatch on its
     * own by forwarding to the configured error page once the listener
     * returns. That forward re-enters the full filter chain -- including
     * Spring Security's {@code anyRequest().authenticated()}, which an
     * anonymous request to {@code /error} fails -- against a response that is
     * already committed as {@code text/event-stream}. That produced a second,
     * worse ERROR-level stack trace on top of the original disconnect on
     * every abrupt (socket-RST) client drop, confirmed at runtime. Completing
     * here is what stops the container from ever reaching that fallback.
     */
    private SseEmitter enregistrer(Set<String> canaux, boolean enveloppe) {
        SseEmitter emitter = new SseEmitter(SANS_EXPIRATION);
        AbonnementSse abonnement = new AbonnementSse(emitter, canaux, enveloppe);
        emitter.onCompletion(() -> retirer(abonnement));
        emitter.onTimeout(() -> detacher(abonnement));
        emitter.onError(erreur -> detacher(abonnement));

        connexions.add(abonnement);
        for (String canal : abonnement.canaux()) {
            // Registered inside compute() so a concurrent retirer() cannot drop
            // the bucket between the lookup and the add.
            abonnes.compute(canal, (cle, liste) -> {
                List<AbonnementSse> cible = liste == null ? new CopyOnWriteArrayList<>() : liste;
                cible.add(abonnement);
                return cible;
            });
        }
        return emitter;
    }

    /** Drops a subscription from every channel it held, and from the roster. */
    private void retirer(AbonnementSse abonnement) {
        connexions.remove(abonnement);
        for (String canal : abonnement.canaux()) {
            abonnes.computeIfPresent(canal, (cle, liste) -> {
                liste.remove(abonnement);
                // Returning null drops the key, so channels for lignes nobody is
                // watching do not accumulate.
                return liste.isEmpty() ? null : liste;
            });
        }
    }

    /**
     * Pushes one delta to every channel it concerns.
     *
     * <p>De-duplicated by subscription identity: a course publishes to its
     * ligne and to each gare it has not yet cleared, so a client watching both
     * {@code ligne:1} and a gare on that ligne matches the same frame twice.
     * Before multiplexing those were two different connections and each got one
     * copy; now they are one connection, which must receive the delta once --
     * but tagged with EVERY one of its channels that the delta concerns, not
     * just the first.
     *
     * <p>Tagging with only the first match is a silent data-loss bug: the client
     * routes on those tags, so a page holding both the map ({@code ligne:1}) and
     * a station board ({@code gare:7}) would see the frame delivered to the map
     * and never to the board, with no error anywhere. Grouping by subscription
     * first is what makes "once per client, to all of its interested consumers"
     * expressible at all.
     */
    public void publier(Collection<String> canaux, String nomEvenement, Object donnees) {
        // Identity-keyed: AbonnementSse has no equals, and the same instance is
        // deliberately stored under each of its channels.
        Map<AbonnementSse, Set<String>> concernes = new IdentityHashMap<>();
        for (String canal : canaux) {
            List<AbonnementSse> liste = abonnes.get(canal);
            if (liste == null) {
                continue;
            }
            for (AbonnementSse abonnement : liste) {
                // LinkedHashSet, so a channel repeated in the publish list is
                // tagged once and the client does not apply the delta twice.
                concernes.computeIfAbsent(abonnement, cle -> new LinkedHashSet<>()).add(canal);
            }
        }

        for (Map.Entry<AbonnementSse, Set<String>> entree : concernes.entrySet()) {
            AbonnementSse abonnement = entree.getKey();
            Set<String> canauxDuClient = entree.getValue();
            envoyer(canauxDuClient.iterator().next(), abonnement,
                    SseEmitter.event().name(nomEvenement)
                            .data(abonnement.charge(canauxDuClient, donnees)));
        }
    }

    /**
     * One scheduled task for every subscription, not one task per subscription:
     * a comment frame every 15 s so proxies do not close an idle stream.
     */
    @Scheduled(fixedRate = 15_000)
    public void battementCoeur() {
        for (AbonnementSse abonnement : connexions) {
            envoyer(premierCanal(abonnement), abonnement, SseEmitter.event().comment("battement"));
        }
    }

    /** Only ever used to name a channel in a log line. */
    private String premierCanal(AbonnementSse abonnement) {
        return abonnement.canaux().stream().findFirst().orElse("?");
    }

    /**
     * A browser {@code EventSource} disconnects on every navigation and every
     * tab close -- that is normal traffic, not an incident, so none of these
     * branches log above DEBUG.
     */
    private void envoyer(String canal, AbonnementSse abonnement, SseEmitter.SseEventBuilder evenement) {
        try {
            abonnement.emitter().send(evenement);
        } catch (AsyncRequestNotUsableException | ClientAbortException e) {
            // The client vanished without a clean close. Unregister here --
            // the callbacks do not always fire on a half-open connection.
            log.debug("Client déconnecté sur le canal {} : {}", canal, e.getMessage());
            detacher(abonnement);
        } catch (IOException e) {
            // A broken pipe surfaces as a plain IOException in some paths, with
            // the more specific exception only as its cause -- unwrap it so the
            // log line is consistent either way.
            Throwable cause = e.getCause();
            if (cause instanceof AsyncRequestNotUsableException || cause instanceof ClientAbortException) {
                log.debug("Client déconnecté sur le canal {} : {}", canal, cause.getMessage());
            } else {
                log.debug("Emitter retiré du canal {} : {}", canal, e.getMessage());
            }
            detacher(abonnement);
        } catch (IllegalStateException e) {
            // The emitter was already completed, by a concurrent send or by
            // the container reclaiming a dead request.
            log.debug("Emitter déjà terminé sur le canal {} : {}", canal, e.getMessage());
            detacher(abonnement);
        }
    }

    /**
     * Unregisters a dead subscription AND completes its emitter. Dropping it
     * from the map alone means nothing ever writes to it again, so with timeout
     * 0 the container never discovers the dead connection either and pins the
     * request and response objects for the life of the process. complete()
     * rather than completeWithError(): the client going away is not a server
     * error, and routing it through the exception handler only produces a
     * stack trace nobody should act on.
     */
    private void detacher(AbonnementSse abonnement) {
        retirer(abonnement);
        try {
            abonnement.emitter().complete();
        } catch (RuntimeException ignore) {
            // Already completed by a concurrent send or by the container.
        }
    }

    /** Subscriber count, for the actuator and for tests that assert no leak. */
    public int nombreAbonnes(String canal) {
        return abonnes.getOrDefault(canal, List.of()).size();
    }

    /**
     * Open connections, counted once each however many channels they carry.
     * The number the multiplexing work exists to hold down.
     */
    public int nombreConnexions() {
        return connexions.size();
    }
}
