package tn.sncft.trino.circulation.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import tn.sncft.trino.circulation.domaine.StatutCourse;
import tn.sncft.trino.circulation.repo.CourseRepository;
import tn.sncft.trino.circulation.repo.PassageGareRepository;
import tn.sncft.trino.circulation.repo.PositionCourseRepository;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers phase 4 job 4: {@code statut} filtering. The interesting behaviour
 * lives at the service boundary, not in the JPQL itself (see the javadoc on
 * {@link tn.sncft.trino.circulation.repo.CourseRepository#rechercher} for why)
 * -- "no filter" must never reach the repository as a null or empty
 * collection, only as every known {@link StatutCourse}.
 */
class CourseServiceTest {

    private final CourseRepository courseRepository = mock(CourseRepository.class);
    private final PassageGareRepository passageGareRepository = mock(PassageGareRepository.class);
    private final PositionCourseRepository positionCourseRepository = mock(PositionCourseRepository.class);
    private final EtatCirculationStore etatStore = mock(EtatCirculationStore.class);
    private final CalculateurEta calculateurEta = mock(CalculateurEta.class);

    private final CourseService service = new CourseService(
            courseRepository, passageGareRepository, positionCourseRepository, etatStore, calculateurEta);

    @Test
    @DisplayName("un seul statut est transmis tel quel au dépôt")
    void unSeulStatutEstTransmisTelQuel() {
        ArgumentCaptor<Collection<StatutCourse>> captor = captor();
        stubRecherche(captor);

        service.lister(null, null, null, List.of(StatutCourse.RETARDE), null, null, 0, 20);

        assertEquals(List.of(StatutCourse.RETARDE), captor.getValue());
    }

    @Test
    @DisplayName("plusieurs statuts (CSV côté contrôleur) sont transmis tels quels au dépôt")
    void plusieursStatutsSontTransmisTelsQuels() {
        ArgumentCaptor<Collection<StatutCourse>> captor = captor();
        stubRecherche(captor);

        service.lister(null, null, null,
                List.of(StatutCourse.EN_CIRCULATION, StatutCourse.RETARDE), null, null, 0, 20);

        assertEquals(List.of(StatutCourse.EN_CIRCULATION, StatutCourse.RETARDE), captor.getValue());
    }

    @Test
    @DisplayName("l'absence de statut se traduit par la liste complète des statuts, jamais par null")
    void absenceDeStatutDonneTousLesStatuts() {
        ArgumentCaptor<Collection<StatutCourse>> captor = captor();
        stubRecherche(captor);

        service.lister(null, null, null, null, null, null, 0, 20);

        assertEquals(StatutCourse.values().length, captor.getValue().size());
        assertTrue(captor.getValue().containsAll(List.of(StatutCourse.values())));
    }

    @Test
    @DisplayName("une liste de statuts vide se traduit aussi par la liste complète des statuts")
    void listeVideDonneTousLesStatuts() {
        ArgumentCaptor<Collection<StatutCourse>> captor = captor();
        stubRecherche(captor);

        service.lister(null, null, null, List.of(), null, null, 0, 20);

        assertEquals(StatutCourse.values().length, captor.getValue().size());
    }

    private void stubRecherche(ArgumentCaptor<Collection<StatutCourse>> captor) {
        Page<tn.sncft.trino.circulation.domaine.Course> vide = new PageImpl<>(List.of());
        when(courseRepository.rechercher(any(), any(), any(), captor.capture(), any(), any(), any()))
                .thenReturn(vide);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Collection<StatutCourse>> captor() {
        return ArgumentCaptor.forClass(Collection.class);
    }
}
