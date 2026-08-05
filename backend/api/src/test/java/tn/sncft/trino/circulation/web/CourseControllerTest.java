package tn.sncft.trino.circulation.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tn.sncft.trino.circulation.domaine.StatutCourse;
import tn.sncft.trino.circulation.dto.CourseResumeDTO;
import tn.sncft.trino.circulation.service.CourseService;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers phase 4 job 4: {@code GET /courses?statut=} bound as
 * {@code List<StatutCourse>}. Security filters are disabled -- {@code
 * /courses} is permitAll anyway, and what is under test here is the CSV
 * binding done by the conversion service, not authorization.
 */
@WebMvcTest(CourseController.class)
@AutoConfigureMockMvc(addFilters = false)
class CourseControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CourseService courseService;

    @SuppressWarnings("unchecked")
    private void stubService() {
        Page<CourseResumeDTO> vide = new PageImpl<>(List.of());
        when(courseService.lister(any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(vide);
    }

    @Test
    @DisplayName("un seul statut continue de fonctionner comme avant")
    void unSeulStatutFonctionneCommeAvant() throws Exception {
        stubService();

        mockMvc.perform(get("/api/v1/courses").param("statut", "RETARDE"))
                .andExpect(status().isOk());

        ArgumentCaptor<List<StatutCourse>> captor = ArgumentCaptor.forClass(List.class);
        verify(courseService).lister(isNull(), isNull(), isNull(), captor.capture(), isNull(), isNull(),
                eq(0), eq(20));
        org.junit.jupiter.api.Assertions.assertEquals(List.of(StatutCourse.RETARDE), captor.getValue());
    }

    @Test
    @DisplayName("une liste CSV de statuts sert une seule requête pour la carte")
    void listeCsvDeStatutsEstAcceptee() throws Exception {
        stubService();

        mockMvc.perform(get("/api/v1/courses").param("statut", "EN_CIRCULATION,RETARDE"))
                .andExpect(status().isOk());

        ArgumentCaptor<List<StatutCourse>> captor = ArgumentCaptor.forClass(List.class);
        verify(courseService).lister(isNull(), isNull(), isNull(), captor.capture(), isNull(), isNull(),
                eq(0), eq(20));
        org.junit.jupiter.api.Assertions.assertEquals(
                List.of(StatutCourse.EN_CIRCULATION, StatutCourse.RETARDE), captor.getValue());
    }

    @Test
    @DisplayName("l'absence de statut continue de signifier aucun filtre")
    void absenceDeStatutNeFiltrePas() throws Exception {
        stubService();

        mockMvc.perform(get("/api/v1/courses"))
                .andExpect(status().isOk());

        verify(courseService).lister(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), eq(0), eq(20));
    }

    @Test
    @DisplayName("une valeur de statut inconnue produit l'enveloppe VALIDATION_ECHOUEE, pas une 500")
    void statutInconnuProduitValidationEchouee() throws Exception {
        mockMvc.perform(get("/api/v1/courses").param("statut", "NIMPORTEQUOI"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ECHOUEE"));
    }
}
