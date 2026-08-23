package tn.sncft.trino.analytique.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tn.sncft.trino.analytique.service.ServiceKpi;
import tn.sncft.trino.analytique.service.ServicePonctualite;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the answer to a dashboard call that omits its date window.
 *
 * <p>Every parameter on this controller is required, and a missing one used to
 * reach the {@code Exception} catch-all in {@code ApiExceptionHandler}: the
 * endpoint answered <b>500 ERREUR_INTERNE</b> and logged a stack trace at ERROR
 * for what is a plain client mistake. It was invisible from the UI, which always
 * sends the dates, and it is exactly what the runbook's own curl line does.
 *
 * <p>Security filters are off: what is under test is argument binding and the
 * error envelope, not the role rule, which {@code ConfigurationSecurite} and the
 * service-level {@code @PreAuthorize} cover between them.
 */
@WebMvcTest(TableauBordController.class)
@AutoConfigureMockMvc(addFilters = false)
class TableauBordControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ServiceKpi serviceKpi;

    @MockitoBean
    private ServicePonctualite servicePonctualite;

    @Test
    @DisplayName("kpi sans date répond 400 VALIDATION_ECHOUEE en nommant le paramètre, jamais 500")
    void kpiSansDateEstUneErreurDeRequete() throws Exception {
        mockMvc.perform(get("/api/v1/tableau-bord/kpi"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ECHOUEE"))
                .andExpect(jsonPath("$.details[0].champ").value("date"))
                .andExpect(jsonPath("$.details[0].probleme").value("obligatoire"));

        // The rejection happens during argument resolution, so the service is
        // never reached -- which is the point: no query runs for a call that
        // could not have been answered.
        verifyNoInteractions(serviceKpi);
    }

    @Test
    @DisplayName("la date est le seul obstacle : fournie, l'appel passe")
    void kpiAvecDatePasse() throws Exception {
        mockMvc.perform(get("/api/v1/tableau-bord/kpi").param("date", "2026-08-20"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("heatmap sans du/au nomme le premier paramètre manquant")
    void heatmapSansFenetreEstUneErreurDeRequete() throws Exception {
        mockMvc.perform(get("/api/v1/tableau-bord/heatmap"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ECHOUEE"))
                .andExpect(jsonPath("$.details[0].champ").value("du"));

        verifyNoInteractions(servicePonctualite);
    }

    @Test
    @DisplayName("une fenêtre à moitié fournie nomme celui qui manque, pas l'autre")
    void heatmapAvecDuSeulNommeAu() throws Exception {
        mockMvc.perform(get("/api/v1/tableau-bord/heatmap").param("du", "2026-08-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].champ").value("au"));
    }

    @Test
    @DisplayName("une date illisible reste une 400 sans détail de champ")
    void dateIllisibleResteUneQuatreCents() throws Exception {
        mockMvc.perform(get("/api/v1/tableau-bord/kpi").param("date", "pas-une-date"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ECHOUEE"));
    }
}
