package tn.sncft.trino.diffusion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers the removal path that does not depend on a servlet container.
 *
 * <p>{@code onCompletion} / {@code onTimeout} / {@code onError} are invoked by
 * Spring MVC when the async request finishes, not by {@link SseEmitter} itself,
 * so a plain unit test cannot fire them -- {@code complete()} outside a request
 * only flips a flag. What is exercised here is the second half of the removal,
 * the one that catches a client which vanished without a clean close: the send
 * fails and the hub unregisters on the spot. That is the case where the
 * callbacks are least likely to arrive, so it is the one worth pinning down.
 */
class HubSseTest {

    private final HubSse hub = new HubSse();

    /** An emitter whose client is gone: any further send throws. */
    private SseEmitter emitterMort(String canal) {
        SseEmitter emitter = hub.abonner(canal);
        emitter.complete();
        return emitter;
    }

    @Test
    @DisplayName("les canaux sont portés par une ligne ou une gare, jamais globaux")
    void lesCanauxSontScopes() {
        assertEquals("ligne:1", HubSse.canalLigne(1L));
        assertEquals("gare:12", HubSse.canalGare(12L));
    }

    @Test
    @DisplayName("un emitter mort est retiré dès la première publication")
    void unEmitterMortEstRetireALaPublication() {
        String canal = HubSse.canalLigne(1L);
        emitterMort(canal);
        assertEquals(1, hub.nombreAbonnes(canal));

        hub.publier(List.of(canal), "position", "{}");

        assertEquals(0, hub.nombreAbonnes(canal));
    }

    @Test
    @DisplayName("un emitter mort est aussi retiré par le battement de cœur")
    void unEmitterMortEstRetireParLeBattement() {
        String canal = HubSse.canalGare(3L);
        emitterMort(canal);

        // The heartbeat is a single scheduled task for every emitter, so it is
        // also the thing most likely to notice a stream nobody is reading.
        hub.battementCoeur();

        assertEquals(0, hub.nombreAbonnes(canal));
    }

    @Test
    @DisplayName("publier sur un canal sans abonné ne lève rien")
    void publierSansAbonneEstUnNoOp() {
        hub.publier(List.of(HubSse.canalLigne(99L)), "position", "{}");
        assertEquals(0, hub.nombreAbonnes(HubSse.canalLigne(99L)));
    }

    @Test
    @DisplayName("un abonné vivant survit au retrait d'un abonné mort sur le même canal")
    void unAbonneVivantSurvitAuRetraitDUnAutre() {
        String canal = HubSse.canalLigne(2L);
        emitterMort(canal);
        hub.abonner(canal);
        assertEquals(2, hub.nombreAbonnes(canal));

        hub.battementCoeur();

        assertEquals(1, hub.nombreAbonnes(canal));
    }
}
