package tn.sncft.trino.notification.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tn.sncft.trino.commun.ConfigurationWeb;
import tn.sncft.trino.commun.LimiteurDebit;
import tn.sncft.trino.iam.service.JetonService;
import tn.sncft.trino.iam.service.UtilisateurService;
import tn.sncft.trino.notification.domaine.CanalType;
import tn.sncft.trino.notification.domaine.CibleType;
import tn.sncft.trino.notification.dto.AbonnementDTO;
import tn.sncft.trino.notification.dto.ResultatAbonnement;
import tn.sncft.trino.notification.service.ServiceAbonnement;
import tn.sncft.trino.securite.ConfigurationSecurite;
import tn.sncft.trino.securite.ResolveurIdentiteAbonne;

import java.time.OffsetDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Following a train without an account, and the two properties that make that
 * safe to expose publicly: the token never travels in a response body, and the
 * unauthenticated write that sends mail is rate-limited.
 */
@WebMvcTest({AbonnementController.class, NotificationController.class})
@Import({ConfigurationSecurite.class, ConfigurationWeb.class, ResolveurIdentiteAbonne.class})
@TestPropertySource(properties = {
        "trino.cors.origines=http://localhost:3000",
        "trino.ingestion.cle=test"
})
class AbonnementSecuriteTest {

    private static final String CORPS = """
            {"cibleType":"COURSE","cibleId":1,"canaux":["IN_APP"]}
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LimiteurDebit limiteurDebit;

    @MockitoBean
    private ServiceAbonnement serviceAbonnement;

    @MockitoBean
    private JetonService jetonService;

    @MockitoBean
    private UtilisateurService utilisateurService;

    @BeforeEach
    void preparer() {
        // Each test starts with a full budget: a fixed window is shared state,
        // and one test's ten calls would otherwise decide another's outcome.
        limiteurDebit.reinitialiser();
        AbonnementDTO abonnement = new AbonnementDTO(1L, CibleType.COURSE, 1L,
                Set.of(CanalType.IN_APP), null, OffsetDateTime.parse("2026-08-12T06:00:00Z"));
        when(serviceAbonnement.enregistrer(any(), any()))
                .thenReturn(new ResultatAbonnement(abonnement, true));
        when(serviceAbonnement.notifications(any(), anyInt(), anyInt())).thenReturn(Page.empty());
    }

    @Test
    @DisplayName("un anonyme s'abonne : 201, et le jeton part en cookie HttpOnly")
    void anonymeSAbonne() throws Exception {
        mockMvc.perform(post("/api/v1/abonnements").contentType("application/json").content(CORPS))
                .andExpect(status().isCreated())
                .andExpect(cookie().exists("jeton_abonne"))
                .andExpect(cookie().httpOnly("jeton_abonne", true));
    }

    /**
     * The token is a bearer credential for one passenger's subscription list.
     * A copy in the response body would be readable by anything on the page,
     * which is the exact thing the HttpOnly cookie exists to prevent.
     */
    @Test
    @DisplayName("le jeton n'apparaît jamais dans le corps de la réponse")
    void jetonAbsentDuCorps() throws Exception {
        String corps = mockMvc.perform(post("/api/v1/abonnements")
                        .contentType("application/json").content(CORPS))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat(corps).doesNotContain("jeton");
    }

    @Test
    @DisplayName("un corps invalide reste un 400 : l'endpoint est public, il n'y a pas de rôle à opposer")
    void corpsInvalideEst400() throws Exception {
        mockMvc.perform(post("/api/v1/abonnements")
                        .contentType("application/json").content("{\"cibleType\":\"COURSE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ECHOUEE"));
    }

    /** EMAIL with nowhere to send is refused, on the one non-field detail path in the API. */
    @Test
    @DisplayName("le canal EMAIL sans adresse est refusé")
    void canalEmailSansAdresseRefuse() throws Exception {
        mockMvc.perform(post("/api/v1/abonnements").contentType("application/json").content("""
                        {"cibleType":"COURSE","cibleId":1,"canaux":["IN_APP","EMAIL"]}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].champ").value("emailRequisPourCanalEmail"));
    }

    /**
     * Ten per minute per IP. It is an unauthenticated write that sends mail:
     * without a limit, one loop posts a thousand messages to any address the
     * caller names.
     */
    @Test
    @DisplayName("au-delà de dix par minute et par IP : 429")
    void limiteDeDebit() throws Exception {
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/v1/abonnements").contentType("application/json").content(CORPS))
                    .andExpect(status().isCreated());
        }
        mockMvc.perform(post("/api/v1/abonnements").contentType("application/json").content(CORPS))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TROP_DE_REQUETES"));
    }

    /** Reading one's own list is neither expensive nor a way to reach anybody else. */
    @Test
    @DisplayName("la lecture n'est pas limitée")
    void lectureNonLimitee() throws Exception {
        for (int i = 0; i < 15; i++) {
            mockMvc.perform(get("/api/v1/abonnements/miennes")).andExpect(status().isOk());
        }
    }

    /**
     * A first-time visitor has no identity and no notifications. That is a normal
     * state on a public portal, not a failure to authenticate -- a 401 here would
     * make the bell render an error for everyone who has never subscribed.
     */
    @Test
    @DisplayName("un visiteur sans identité reçoit une page vide, pas un 401")
    void visiteurSansIdentiteRecoitUnePageVide() throws Exception {
        mockMvc.perform(get("/api/v1/notifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"contenu\":[]")));
    }

    @Test
    @DisplayName("les abonnements d'un visiteur sans identité sont une liste vide")
    void miennesSansIdentite() throws Exception {
        mockMvc.perform(get("/api/v1/abonnements/miennes"))
                .andExpect(status().isOk())
                .andExpect(content().string("[]"));
    }
}
