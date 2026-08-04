package tn.sncft.trino.diffusion.web;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tn.sncft.trino.diffusion.HubSse;

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

    private final HubSse hubSse;

    public StreamController(HubSse hubSse) {
        this.hubSse = hubSse;
    }

    @GetMapping(value = "/lignes/{ligneId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter ligne(@PathVariable Long ligneId) {
        return hubSse.abonner(HubSse.canalLigne(ligneId));
    }

    @GetMapping(value = "/gares/{gareId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter gare(@PathVariable Long gareId) {
        return hubSse.abonner(HubSse.canalGare(gareId));
    }
}
