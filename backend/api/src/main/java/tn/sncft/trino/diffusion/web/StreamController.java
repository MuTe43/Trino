package tn.sncft.trino.diffusion.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tn.sncft.trino.diffusion.HubSse;
import tn.sncft.trino.securite.IdentiteAbonne;
import tn.sncft.trino.securite.ResolveurIdentiteAbonne;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The live channels. Public and anonymous per api-contract.md: the passenger
 * portal and the station boards have no one to log in.
 *
 * <p>Deltas only. The client fetches its initial snapshot over REST and then
 * applies what arrives here; a full course list on this stream would defeat the
 * point of the channel.
 */
@RestController
@RequestMapping("/api/v1/stream")
public class StreamController {

    /**
     * Upper bound on channels per connection. The network has five lignes and
     * around forty gares, so nothing legitimate comes close; this only stops a
     * caller from pinning arbitrary server memory with one request.
     */
    private static final int MAX_CANAUX = 64;

    private final HubSse hubSse;
    private final ResolveurIdentiteAbonne resolveurIdentiteAbonne;

    public StreamController(HubSse hubSse, ResolveurIdentiteAbonne resolveurIdentiteAbonne) {
        this.hubSse = hubSse;
        this.resolveurIdentiteAbonne = resolveurIdentiteAbonne;
    }

    /**
     * Multiplexed stream: {@code /stream?lignes=1,2,3&gares=7}.
     *
     * <p>One connection per client rather than one per channel. A browser
     * allows roughly six connections per origin over HTTP/1.1 and the API is a
     * single origin, so the previous shape -- one socket per ligne -- put the
     * map at five of that budget on the default view and left REST calls
     * queueing behind the streams.
     *
     * <p>Still invariant 5: the subscription list is explicit, so a client
     * receives only the channels it named. There is no way to ask for
     * everything.
     *
     * <p>The one channel a client does <em>not</em> name is its own
     * {@code abonne:} channel (phase 8). It is derived here from the caller's
     * own credential -- the {@code X-Abonne} header or the {@code jeton_abonne}
     * cookie -- and there is deliberately no {@code abonnes=} parameter: the
     * subscription list is client-supplied, so a parameter naming this channel
     * would let anyone stream another passenger's notifications by guessing or
     * stealing one token. Any {@code abonnes=} sent by a client is simply not
     * read.
     */
    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter multiplex(@RequestParam(name = "lignes", required = false) List<Long> lignes,
                                @RequestParam(name = "gares", required = false) List<Long> gares,
                                HttpServletRequest requete) {
        // LinkedHashSet: de-duplicates a repeated id and keeps the caller's
        // order, which only matters for readable log lines.
        Set<String> canaux = new LinkedHashSet<>();
        if (lignes != null) {
            lignes.forEach(id -> canaux.add(HubSse.canalLigne(id)));
        }
        if (gares != null) {
            gares.forEach(id -> canaux.add(HubSse.canalGare(id)));
        }
        canalAbonne(requete).ifPresent(canaux::add);
        // IllegalArgumentException is what ApiExceptionHandler turns into a 400
        // VALIDATION_ECHOUEE envelope. An empty subscription would otherwise
        // open a connection that can never receive anything -- exactly the
        // global-channel shape invariant 5 forbids, only silent.
        if (canaux.isEmpty()) {
            throw new IllegalArgumentException("Au moins une ligne ou une gare doit être demandée.");
        }
        if (canaux.size() > MAX_CANAUX) {
            throw new IllegalArgumentException("Trop de canaux demandés (maximum " + MAX_CANAUX + ").");
        }
        return hubSse.abonnerMultiplex(canaux);
    }

    @GetMapping(value = "/lignes/{ligneId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter ligne(@PathVariable Long ligneId) {
        return hubSse.abonner(HubSse.canalLigne(ligneId));
    }

    @GetMapping(value = "/gares/{gareId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter gare(@PathVariable Long gareId) {
        return hubSse.abonner(HubSse.canalGare(gareId));
    }

    /**
     * This connection's private notification channel, or empty for a visitor who
     * has never subscribed to anything.
     *
     * <p>Only on the multiplexed endpoint. The two per-path ones stay the bare,
     * single-channel shape the kiosk board relies on.
     */
    private Optional<String> canalAbonne(HttpServletRequest requete) {
        return resolveurIdentiteAbonne.resoudre(requete)
                .map(identite -> identite.estAnonyme()
                        ? HubSse.canalAbonneJeton(identite.jeton())
                        : HubSse.canalAbonneCompte(identite.utilisateurId()));
    }
}
