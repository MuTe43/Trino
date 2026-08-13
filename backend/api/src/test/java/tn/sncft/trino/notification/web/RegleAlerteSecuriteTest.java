package tn.sncft.trino.notification.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tn.sncft.trino.iam.domaine.Role;
import tn.sncft.trino.iam.dto.UtilisateurDTO;
import tn.sncft.trino.iam.service.JetonService;
import tn.sncft.trino.iam.service.UtilisateurService;
import tn.sncft.trino.notification.dto.RegleAlerteDTO;
import tn.sncft.trino.notification.service.ServiceRegleAlerte;
import tn.sncft.trino.securite.ConfigurationSecurite;

import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The alert-rule URL rules, through the real filter chain.
 *
 * <p>Invariant 9, both halves. {@code @PreAuthorize} on the service alone would
 * answer 400 to the last test here rather than 403: {@code @Valid} runs during
 * controller argument resolution, before the AOP proxy behind the annotation
 * exists, so a forbidden caller with a malformed body would be told their
 * payload was wrong on an endpoint they were never allowed to touch. Only the
 * URL rule, which runs in the filter chain ahead of validation, gets it right --
 * and only a test that goes through that chain can see the difference.
 *
 * <p>Roles are carried by a real {@code Authorization} header against a stubbed
 * {@link JetonService}, as in {@code IncidentSecuriteTest}:
 * {@code spring-security-test} is not a dependency of this module, and this way
 * {@code FiltreJwt} is exercised on the way in.
 */
@WebMvcTest(RegleAlerteController.class)
@Import(ConfigurationSecurite.class)
@TestPropertySource(properties = {
        "trino.cors.origines=http://localhost:3000",
        "trino.ingestion.cle=test"
})
class RegleAlerteSecuriteTest {

    private static final String REGLE = """
            {"evenement":"RETARD_SEUIL","seuilMin":15,"canaux":["IN_APP"],"actif":true}
            """;

    /** Not a valid event, and no channel: validation would answer 400 if it ran. */
    private static final String REGLE_INVALIDE = """
            {"evenement":"PAS_UN_EVENEMENT","seuilMin":-4,"canaux":[]}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ServiceRegleAlerte serviceRegleAlerte;

    @MockitoBean
    private JetonService jetonService;

    @MockitoBean
    private UtilisateurService utilisateurService;

    @BeforeEach
    void preparer() {
        RegleAlerteDTO regle = new RegleAlerteDTO(1L, tn.sncft.trino.notification.domaine.Evenement.RETARD_SEUIL,
                (short) 15, null, Set.of(tn.sncft.trino.notification.domaine.CanalType.IN_APP),
                true, 1L, "Admin");
        when(serviceRegleAlerte.lister()).thenReturn(List.of(regle));
        when(serviceRegleAlerte.creer(any())).thenReturn(regle);
        when(serviceRegleAlerte.mettreAJour(anyLong(), any())).thenReturn(regle);

        for (Role role : Role.values()) {
            String jeton = jeton(role);
            String email = role.name().toLowerCase() + "@sncft.tn";
            when(jetonService.estValide(jeton)).thenReturn(true);
            when(jetonService.extraireEmail(jeton)).thenReturn(email);
            when(utilisateurService.moi(email))
                    .thenReturn(new UtilisateurDTO(1L, email, role.name(), role, true));
        }
    }

    private static String jeton(Role role) {
        return "jeton-" + role.name();
    }

    private static MockHttpServletRequestBuilder en(MockHttpServletRequestBuilder requete, Role role) {
        return requete.header("Authorization", "Bearer " + jeton(role));
    }

    @Test
    @DisplayName("un administrateur liste, crée et modifie")
    void administrateurGereLesRegles() throws Exception {
        mockMvc.perform(en(get("/api/v1/regles-alerte"), Role.ADMINISTRATEUR))
                .andExpect(status().isOk());
        mockMvc.perform(en(post("/api/v1/regles-alerte"), Role.ADMINISTRATEUR)
                        .contentType("application/json").content(REGLE))
                .andExpect(status().isCreated());
        mockMvc.perform(en(patch("/api/v1/regles-alerte/1"), Role.ADMINISTRATEUR)
                        .contentType("application/json").content("{\"actif\":false}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("un voyageur est refusé, comme l'exige la commande d'acceptation")
    void voyageurEstRefuse() throws Exception {
        mockMvc.perform(en(get("/api/v1/regles-alerte"), Role.VOYAGEUR))
                .andExpect(status().isForbidden());
    }

    /**
     * The other two operational roles are excluded for the same reason
     * ADMINISTRATEUR is excluded from the dashboards: configuring what the system
     * notifies about is administration, not exploitation.
     */
    @Test
    @DisplayName("agent et responsable sont refusés : configurer les alertes est une tâche d'administration")
    void rolesOperationnelsRefuses() throws Exception {
        mockMvc.perform(en(get("/api/v1/regles-alerte"), Role.AGENT_CIRCULATION))
                .andExpect(status().isForbidden());
        mockMvc.perform(en(get("/api/v1/regles-alerte"), Role.RESPONSABLE_EXPLOITATION))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("un anonyme reçoit 401, pas 403")
    void anonymeEstNonAuthentifie() throws Exception {
        mockMvc.perform(get("/api/v1/regles-alerte")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("rôle interdit + corps invalide : 403, jamais 400")
    void roleInterditGagneSurLaValidation() throws Exception {
        mockMvc.perform(en(post("/api/v1/regles-alerte"), Role.VOYAGEUR)
                        .contentType("application/json").content(REGLE_INVALIDE))
                .andExpect(status().isForbidden());
        mockMvc.perform(en(patch("/api/v1/regles-alerte/1"), Role.VOYAGEUR)
                        .contentType("application/json").content("pas du json"))
                .andExpect(status().isForbidden());
    }
}
