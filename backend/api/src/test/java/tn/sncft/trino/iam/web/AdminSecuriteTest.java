package tn.sncft.trino.iam.web;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tn.sncft.trino.iam.domaine.Role;
import tn.sncft.trino.iam.dto.UtilisateurCreeDTO;
import tn.sncft.trino.iam.dto.UtilisateurDTO;
import tn.sncft.trino.iam.service.JetonService;
import tn.sncft.trino.iam.service.JournalService;
import tn.sncft.trino.iam.service.UtilisateurService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The utilisateurs / journal-connexions URL rules, run through the real
 * filter chain -- see {@code exploitation.web.IncidentSecuriteTest}, whose
 * shape this copies exactly.
 *
 * <p>The reason this file exists is {@link #roleInterditPlusCorpsInvalideRend403PasQue400}:
 * invariant 9 requires the URL rule to run ahead of {@code @Valid}, so a
 * forbidden caller sending a malformed body must see 403, never 400. Roles
 * are carried by a real {@code Authorization} header against a stubbed
 * {@link JetonService}, not {@code @WithMockUser} -- {@code spring-security-test}
 * is not a dependency of this module.
 */
@WebMvcTest({UtilisateurController.class, JournalController.class})
@Import(tn.sncft.trino.securite.ConfigurationSecurite.class)
@TestPropertySource(properties = {
        "trino.cors.origines=http://localhost:3000",
        "trino.ingestion.cle=test"
})
class AdminSecuriteTest {

    private static final String CREATION = """
            {"email":"nouveau@sncft.tn","nom":"Nouveau Compte","role":"AGENT_CIRCULATION"}
            """;

    /** Missing nom, invalid email, invalid role -- would fail validation if it ever ran. */
    private static final String CREATION_INVALIDE = """
            {"email":"pas-un-email","role":"PAS_UN_ROLE"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UtilisateurService utilisateurService;

    @MockitoBean
    private JournalService journalService;

    @MockitoBean
    private JetonService jetonService;

    @BeforeEach
    void preparer() {
        UtilisateurCreeDTO cree = new UtilisateurCreeDTO(9L, "nouveau@sncft.tn", "Nouveau Compte",
                Role.AGENT_CIRCULATION, true, "MotDePasseXY12");
        UtilisateurDTO dto = new UtilisateurDTO(9L, "nouveau@sncft.tn", "Nouveau Compte",
                Role.AGENT_CIRCULATION, true);
        when(utilisateurService.creer(any())).thenReturn(cree);
        when(utilisateurService.mettreAJour(anyLong(), any())).thenReturn(dto);
        when(utilisateurService.reinitialiserMotDePasse(anyLong())).thenReturn(cree);
        when(utilisateurService.lister(anyInt(), anyInt())).thenReturn(Page.empty());
        when(utilisateurService.trouverParId(anyLong())).thenReturn(dto);
        when(journalService.consulter(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(Page.empty());

        // One stubbed token per role, resolved by the real FiltreJwt.
        for (Role role : Role.values()) {
            String jeton = jeton(role);
            when(jetonService.estValide(jeton)).thenReturn(true);
            when(jetonService.extraireEmail(jeton)).thenReturn(role.name().toLowerCase() + "@sncft.tn");
            when(utilisateurService.moi(role.name().toLowerCase() + "@sncft.tn"))
                    .thenReturn(new UtilisateurDTO(1L, role.name().toLowerCase() + "@sncft.tn",
                            role.name(), role, true));
        }
    }

    private static String jeton(Role role) {
        return "jeton-" + role.name();
    }

    private static MockHttpServletRequestBuilder en(MockHttpServletRequestBuilder requete, Role role) {
        return requete.header("Authorization", "Bearer " + jeton(role));
    }

    @Test
    @DisplayName("un responsable ne consulte pas le journal de connexions")
    void responsableEstRefuseSurLeJournal() throws Exception {
        mockMvc.perform(en(get("/api/v1/journal-connexions"), Role.RESPONSABLE_EXPLOITATION))
                .andExpect(status().isForbidden());
    }

    /**
     * Invariant 9. {@code @Valid} runs during controller argument resolution,
     * before the AOP proxy behind {@code @PreAuthorize} is reached, so a role
     * check placed only on the service would answer 400 here -- telling a
     * caller their payload was malformed on an endpoint they were never
     * allowed to touch. The URL rule runs earlier, in the filter chain.
     */
    @Test
    @DisplayName("un responsable avec un corps invalide reçoit 403, jamais 400")
    void roleInterditPlusCorpsInvalideRend403PasQue400() throws Exception {
        mockMvc.perform(en(post("/api/v1/utilisateurs"), Role.RESPONSABLE_EXPLOITATION)
                        .contentType("application/json").content(CREATION_INVALIDE))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("un voyageur ne liste pas les comptes")
    void voyageurEstRefuseSurLaListe() throws Exception {
        mockMvc.perform(en(get("/api/v1/utilisateurs"), Role.VOYAGEUR)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("un anonyme reçoit 401, pas 403")
    void anonymeEstNonAuthentifie() throws Exception {
        mockMvc.perform(get("/api/v1/utilisateurs")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("un administrateur crée un compte")
    void administrateurCree() throws Exception {
        mockMvc.perform(en(post("/api/v1/utilisateurs"), Role.ADMINISTRATEUR)
                        .contentType("application/json").content(CREATION))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("un administrateur met à jour un compte")
    void administrateurMetAJour() throws Exception {
        mockMvc.perform(en(patch("/api/v1/utilisateurs/9"), Role.ADMINISTRATEUR)
                        .contentType("application/json").content("{\"actif\":false}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("un administrateur réinitialise un mot de passe")
    void administrateurReinitialiseMotDePasse() throws Exception {
        mockMvc.perform(en(post("/api/v1/utilisateurs/9/mot-de-passe"), Role.ADMINISTRATEUR))
                .andExpect(status().isOk());
    }
}
