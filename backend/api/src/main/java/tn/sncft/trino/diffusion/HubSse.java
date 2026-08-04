package tn.sncft.trino.diffusion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Fan-out for the live channels. SSE rather than WebSocket because traffic is
 * server to client only (decision 3).
 *
 * <p>Channels are scoped: {@code ligne:{id}} and {@code gare:{id}}, never a
 * global one. A station board subscribes to its own gare and receives nothing
 * about the rest of the network.
 */
@Component
public class HubSse {

    private static final Logger log = LoggerFactory.getLogger(HubSse.class);

    /** Never time out. Reconnection is the client's job, via Last-Event-ID. */
    private static final long SANS_EXPIRATION = 0L;

    private final Map<String, List<SseEmitter>> abonnes = new ConcurrentHashMap<>();

    public static String canalLigne(Long ligneId) {
        return "ligne:" + ligneId;
    }

    public static String canalGare(Long gareId) {
        return "gare:" + gareId;
    }

    /**
     * Registers a subscriber. The three removal callbacks are written before
     * anything else on purpose: an emitter that outlives its client is the
     * classic leak in this design, and all three of completion, timeout and
     * error have to unregister or the map grows for the life of the process.
     */
    public SseEmitter abonner(String canal) {
        SseEmitter emitter = new SseEmitter(SANS_EXPIRATION);
        emitter.onCompletion(() -> retirer(canal, emitter));
        emitter.onTimeout(() -> retirer(canal, emitter));
        emitter.onError(erreur -> retirer(canal, emitter));

        // Registered inside compute() so a concurrent retirer() cannot drop the
        // bucket between the lookup and the add.
        abonnes.compute(canal, (cle, liste) -> {
            List<SseEmitter> cible = liste == null ? new CopyOnWriteArrayList<>() : liste;
            cible.add(emitter);
            return cible;
        });
        return emitter;
    }

    private void retirer(String canal, SseEmitter emitter) {
        abonnes.computeIfPresent(canal, (cle, liste) -> {
            liste.remove(emitter);
            // Returning null drops the key, so channels for lignes nobody is
            // watching do not accumulate.
            return liste.isEmpty() ? null : liste;
        });
    }

    /** Pushes one delta to every channel it concerns. */
    public void publier(Collection<String> canaux, String nomEvenement, Object donnees) {
        for (String canal : canaux) {
            List<SseEmitter> liste = abonnes.get(canal);
            if (liste == null) {
                continue;
            }
            for (SseEmitter emitter : liste) {
                envoyer(canal, emitter, SseEmitter.event().name(nomEvenement).data(donnees));
            }
        }
    }

    /**
     * One scheduled task for every emitter, not one task per emitter: a comment
     * frame every 15 s so proxies do not close an idle stream.
     */
    @Scheduled(fixedRate = 15_000)
    public void battementCoeur() {
        abonnes.forEach((canal, liste) -> {
            for (SseEmitter emitter : liste) {
                envoyer(canal, emitter, SseEmitter.event().comment("battement"));
            }
        });
    }

    private void envoyer(String canal, SseEmitter emitter, SseEmitter.SseEventBuilder evenement) {
        try {
            emitter.send(evenement);
        } catch (IOException | IllegalStateException e) {
            // The client vanished without a clean close. Unregister here --
            // the callbacks do not always fire on a half-open connection.
            log.debug("Emitter retiré du canal {} : {}", canal, e.getMessage());
            retirer(canal, emitter);
            // ...and complete it. Dropping it from the map alone means nothing
            // ever writes to it again, so with timeout 0 the container never
            // discovers the dead connection either and pins the request and
            // response objects for the life of the process. complete() rather
            // than completeWithError(): the client going away is not a server
            // error, and routing it through the exception handler only
            // produces a stack trace nobody should act on.
            try {
                emitter.complete();
            } catch (RuntimeException ignore) {
                // Already completed by a concurrent send or by the container.
            }
        }
    }

    /** Subscriber count, for the actuator and for tests that assert no leak. */
    public int nombreAbonnes(String canal) {
        return abonnes.getOrDefault(canal, List.of()).size();
    }
}
