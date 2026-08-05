package tn.sncft.trino.circulation.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tn.sncft.trino.circulation.domaine.Course;
import tn.sncft.trino.circulation.domaine.PassageGare;
import tn.sncft.trino.circulation.domaine.SensCourse;
import tn.sncft.trino.circulation.domaine.StatutCourse;
import tn.sncft.trino.circulation.dto.DepartGareDTO;
import tn.sncft.trino.circulation.repo.PassageGareRepository;
import tn.sncft.trino.referentiel.domaine.Gare;
import tn.sncft.trino.referentiel.domaine.Train;
import tn.sncft.trino.referentiel.domaine.TypeTrain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DepartsServiceTest {

    private static final OffsetDateTime BASE = OffsetDateTime.of(2026, 8, 4, 10, 0, 0, 0, ZoneOffset.UTC);

    private final PassageGareRepository passageGareRepository = mock(PassageGareRepository.class);
    private final HorlogeCirculation horloge = mock(HorlogeCirculation.class);
    private final DepartsService service = new DepartsService(passageGareRepository, horloge);

    @Test
    @DisplayName("la destination est le nom de la gare du dernier passage de la course")
    void laDestinationEstLeNomDuTerminus() {
        when(horloge.maintenant()).thenReturn(BASE);

        Course course = course(1L, StatutCourse.EN_CIRCULATION, 0);
        PassageGare depart = passage(course, "Sousse", "2", BASE.plusMinutes(37), BASE.plusMinutes(37));
        when(passageGareRepository.findDeparts(anyLong(), any(), any(), anyCollection(), any()))
                .thenReturn(List.of(depart));
        when(passageGareRepository.findTerminusGares(anyCollection()))
                .thenReturn(List.of(terminus(course.getId(), "Gabès")));

        List<DepartGareDTO> departs = service.prochainsDeparts(12L, 20);

        assertEquals(1, departs.size());
        assertEquals("Gabès", departs.get(0).destination());
    }

    @Test
    @DisplayName("l'ordre du dépôt suit departEstime : un train retardé passe après un train à l'heure parti plus tard")
    void lOrdreSuitLeDepartEstime() {
        when(horloge.maintenant()).thenReturn(BASE);

        // Train A: theoretical 10:00, but 25 min late -> estimate 10:25.
        Course courseA = course(1L, StatutCourse.RETARDE, 25);
        PassageGare departA = passage(courseA, "Sousse", "1", BASE, BASE.plusMinutes(25));

        // Train B: theoretical 10:10, on time -> estimate 10:10. Later
        // theoretical departure than A, but leaves first once A's delay is
        // accounted for.
        Course courseB = course(2L, StatutCourse.A_QUAI, 0);
        PassageGare departB = passage(courseB, "Monastir", "2", BASE.plusMinutes(10), BASE.plusMinutes(10));

        // The repository query orders by departEstimee asc; the service must
        // not undo that ordering, so the mock hands back the already-sorted
        // result the way the real query would.
        when(passageGareRepository.findDeparts(anyLong(), any(), any(), anyCollection(), any()))
                .thenReturn(List.of(departB, departA));
        when(passageGareRepository.findTerminusGares(anyCollection())).thenReturn(List.of());

        List<DepartGareDTO> departs = service.prochainsDeparts(12L, 20);

        assertEquals(2, departs.size());
        assertEquals(courseB.getId(), departs.get(0).courseId(), "le train à l'heure vient en premier");
        assertEquals(courseA.getId(), departs.get(1).courseId(), "le train retardé tombe derrière lui");
    }

    @Test
    @DisplayName("une course ANNULE reste présente au tableau, pas exclue")
    void uneCourseAnnuleResteAffichee() {
        when(horloge.maintenant()).thenReturn(BASE);

        Course courseAnnulee = course(3L, StatutCourse.ANNULE, 0);
        PassageGare departAnnule = passage(courseAnnulee, "Sfax", "3", BASE.plusMinutes(5), BASE.plusMinutes(5));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<StatutCourse>> statutsCaptor = ArgumentCaptor.forClass(Collection.class);
        when(passageGareRepository.findDeparts(anyLong(), any(), any(), statutsCaptor.capture(), any()))
                .thenReturn(List.of(departAnnule));
        when(passageGareRepository.findTerminusGares(anyCollection())).thenReturn(List.of());

        List<DepartGareDTO> departs = service.prochainsDeparts(12L, 20);

        assertEquals(1, departs.size());
        assertEquals(StatutCourse.ANNULE, departs.get(0).statut());
        assertFalse(statutsCaptor.getValue().contains(StatutCourse.ANNULE),
                "ANNULE ne doit pas figurer dans les statuts exclus de la requête");
    }

    private static Course course(Long id, StatutCourse statut, int retardMin) {
        Train train = new Train();
        train.setId(id);
        train.setNumero("DR" + id);
        train.setNom("Train " + id);
        train.setType(TypeTrain.GRANDES_LIGNES);

        Course course = new Course();
        course.setId(id);
        course.setTrain(train);
        course.setDateService(LocalDate.of(2026, 8, 4));
        course.setSens(SensCourse.ALLER);
        course.setDepartTheorique(BASE);
        course.setArriveeTheorique(BASE.plusHours(2));
        course.setStatut(statut);
        course.setRetardMin(retardMin);
        course.setAvancementKm(BigDecimal.ZERO);
        return course;
    }

    private static PassageGare passage(Course course, String gareNom, String quai,
                                       OffsetDateTime departTheorique, OffsetDateTime departEstimee) {
        Gare gare = new Gare();
        gare.setId(100L + course.getId());
        gare.setNom(gareNom);

        PassageGare passage = new PassageGare();
        passage.setId(200L + course.getId());
        passage.setCourse(course);
        passage.setGare(gare);
        passage.setOrdre((short) 1);
        passage.setPkKm(BigDecimal.ZERO);
        passage.setQuai(quai);
        passage.setDepartTheorique(departTheorique);
        passage.setDepartEstimee(departEstimee);
        return passage;
    }

    private static PassageGareRepository.TerminusProjection terminus(Long courseId, String nom) {
        return new PassageGareRepository.TerminusProjection() {
            @Override
            public Long getCourseId() {
                return courseId;
            }

            @Override
            public String getNom() {
                return nom;
            }
        };
    }
}
