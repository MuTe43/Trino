package tn.sncft.trino.diffusion.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tn.sncft.trino.diffusion.HubSse;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers the multiplexed stream added in phase 5: several channels over one
 * connection, so the map stops spending the browser's ~6-per-origin HTTP/1.1
 * budget on one socket per ligne.
 *
 * <p>The real {@link HubSse} is imported rather than mocked -- what is under
 * test is the fan-out and the frame shape, which a mock would assert nothing
 * about. Security filters are off: these endpoints are permitAll anyway.
 */
@WebMvcTest(StreamController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(HubSse.class)
// A MockMvc async request is never completed by a client, so every subscription
// a test opens outlives it. Sharing one cached context would leave the hub
// counting the previous test's connections, which is exactly the number these
// tests exist to assert on.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class StreamControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private HubSse hub;

    private MvcResult ouvrirMultiplex(String lignes, String gares) throws Exception {
        return mockMvc.perform(get("/api/v1/stream").param("lignes", lignes).param("gares", gares))
                .andExpect(request().asyncStarted())
                .andReturn();
    }

    @Test
    @DisplayName("un abonnement multiplexé n'ouvre qu'une connexion pour tous ses canaux")
    void unSeulEmetteurPourPlusieursCanaux() throws Exception {
        ouvrirMultiplex("1,2,3", "7");

        // The point of the whole exercise: four channels, one socket.
        assertEquals(1, hub.nombreConnexions());
        assertEquals(1, hub.nombreAbonnes(HubSse.canalLigne(1L)));
        assertEquals(1, hub.nombreAbonnes(HubSse.canalLigne(2L)));
        assertEquals(1, hub.nombreAbonnes(HubSse.canalLigne(3L)));
        assertEquals(1, hub.nombreAbonnes(HubSse.canalGare(7L)));
    }

    @Test
    @DisplayName("chaque trame porte l'identité de son canal")
    void chaqueTramePorteSonCanal() throws Exception {
        MvcResult resultat = ouvrirMultiplex("1", "7");

        hub.publier(List.of(HubSse.canalLigne(1L)), "position", Map.of("courseId", 4821));

        String corps = resultat.getResponse().getContentAsString();
        assertTrue(corps.contains("event:position"), corps);
        assertTrue(corps.contains("\"canaux\":[\"ligne:1\"]"), corps);
        assertTrue(corps.contains("\"courseId\":4821"), corps);
    }

    /**
     * The other half of the de-duplication contract, and the half that was
     * missing: sending the delta once must not mean delivering it to only one
     * of the client's channels. The client routes on these tags, so a frame
     * that named just the first match would leave a station board on the same
     * page silently un-updated -- no error, no reconnect, just a table that
     * stops moving.
     */
    @Test
    @DisplayName("une trame unique porte tous les canaux du client qu'elle concerne")
    void laTrameUniquePorteTousLesCanauxConcernes() throws Exception {
        MvcResult resultat = ouvrirMultiplex("1", "7");

        hub.publier(List.of(HubSse.canalLigne(1L), HubSse.canalGare(7L)), "position",
                Map.of("courseId", 4821));

        String corps = resultat.getResponse().getContentAsString();
        assertEquals(1, occurrences(corps, "event:position"), corps);
        assertTrue(corps.contains("\"ligne:1\""), corps);
        assertTrue(corps.contains("\"gare:7\""), corps);
    }

    /**
     * A course publishes to its ligne and to every gare it has not yet cleared
     * (see {@code DiffuseurCirculation.canaux}). Two of those channels on two
     * separate connections used to mean one copy each; on one connection it
     * must still be one copy, not two.
     */
    @Test
    @DisplayName("une trame touchant deux canaux abonnés n'est envoyée qu'une fois")
    void pasDeDoublonQuandDeuxCanauxCorrespondent() throws Exception {
        MvcResult resultat = ouvrirMultiplex("1", "7");

        hub.publier(List.of(HubSse.canalLigne(1L), HubSse.canalGare(7L)), "position",
                Map.of("courseId", 4821));

        String corps = resultat.getResponse().getContentAsString();
        assertEquals(1, occurrences(corps, "event:position"), corps);
    }

    /**
     * Compared between two connections rather than counted on one, and that is
     * load-bearing. {@code @EnableScheduling} sits on {@code TrinoApplication},
     * the {@code @SpringBootConfiguration} this slice bootstraps from, so
     * {@link HubSse#battementCoeur()} is genuinely scheduled here too and its
     * {@code fixedRate} timer fires on its own schedule.
     *
     * <p>Phase 5 measured a delta around the explicit call, which narrowed the
     * race but did not close it: a scheduled firing landing between the two
     * reads still adds a frame, and it did exactly that during phase 6's verify
     * run. There is no window to shrink here -- a stray beat reaches BOTH
     * connections, so comparing them cancels it whenever it lands.
     *
     * <p>The claim being pinned is the one that matters: a heartbeat costs a
     * client one frame per CONNECTION, so a four-channel client and a
     * one-channel client receive the same number. One frame per channel would
     * make it four against one.
     */
    @Test
    @DisplayName("le battement de cœur n'envoie qu'une trame par connexion, pas une par canal")
    void unSeulBattementParConnexion() throws Exception {
        MvcResult quatreCanaux = ouvrirMultiplex("1,2,3", "7");
        MvcResult unCanal = ouvrirMultiplex("4", "");
        int avantQuatre = occurrences(quatreCanaux.getResponse().getContentAsString(), "battement");
        int avantUn = occurrences(unCanal.getResponse().getContentAsString(), "battement");

        hub.battementCoeur();

        int deltaQuatre = occurrences(quatreCanaux.getResponse().getContentAsString(), "battement") - avantQuatre;
        int deltaUn = occurrences(unCanal.getResponse().getContentAsString(), "battement") - avantUn;

        assertEquals(deltaUn, deltaQuatre,
                "quatre canaux ont reçu " + deltaQuatre + " trames contre " + deltaUn
                        + " pour un seul : le battement est émis par canal, pas par connexion");
        assertTrue(deltaQuatre >= 1, "aucune trame reçue pour un battement explicite");
    }

    /**
     * The kiosk board keeps the simple endpoint, and its payload shape is the
     * one api-contract.md documents -- the bare delta, no envelope to unwrap.
     */
    @Test
    @DisplayName("le canal simple garde la charge utile nue documentée au contrat")
    void leCanalSimpleResteNu() throws Exception {
        MvcResult resultat = mockMvc.perform(get("/api/v1/stream/gares/7"))
                .andExpect(request().asyncStarted())
                .andReturn();

        hub.publier(List.of(HubSse.canalGare(7L)), "position", Map.of("courseId", 4821));

        String corps = resultat.getResponse().getContentAsString();
        assertTrue(corps.contains("\"courseId\":4821"), corps);
        assertFalse(corps.contains("\"canal\""), corps);
    }

    @Test
    @DisplayName("un abonnement vide est refusé plutôt que d'ouvrir un flux muet")
    void abonnementVideRefuse() throws Exception {
        mockMvc.perform(get("/api/v1/stream"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ECHOUEE"));
    }

    private static int occurrences(String corps, String motif) {
        int total = 0;
        for (int index = corps.indexOf(motif); index >= 0; index = corps.indexOf(motif, index + motif.length())) {
            total++;
        }
        return total;
    }
}
