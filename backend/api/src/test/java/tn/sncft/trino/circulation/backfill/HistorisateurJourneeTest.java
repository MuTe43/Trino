package tn.sncft.trino.circulation.backfill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tn.sncft.trino.circulation.domaine.Course;
import tn.sncft.trino.circulation.domaine.PassageGare;
import tn.sncft.trino.circulation.domaine.SensCourse;
import tn.sncft.trino.circulation.domaine.StatutCourse;
import tn.sncft.trino.circulation.repo.CourseRepository;
import tn.sncft.trino.circulation.repo.PassageGareRepository;
import tn.sncft.trino.circulation.service.GenerateurCourses;
import tn.sncft.trino.referentiel.domaine.Gare;
import tn.sncft.trino.referentiel.domaine.Train;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The backfill's contract: a finished day that looks like one the delay engine
 * tracked, reproducible across runs, and written without inventing positions.
 *
 * <p>Nothing here asserts "no {@code position_course} rows" by counting them --
 * that guarantee is structural. {@link HistorisateurJournee} has no position
 * repository to write through, so the only way to break it is to add one, which
 * is a visible change rather than a silent regression.
 */
class HistorisateurJourneeTest {

    private static final LocalDate DATE = LocalDate.of(2026, 7, 26);
    private static final OffsetDateTime DEPART =
            OffsetDateTime.of(2026, 7, 26, 6, 0, 0, 0, ZoneOffset.UTC);

    /** Four stops over 94 minutes, the shape the engine tests already use. */
    private static Course course(long trainId) {
        Train train = new Train();
        train.setId(trainId);
        train.setNumero("TN" + trainId);

        Course course = new Course();
        course.setId(trainId);
        course.setTrain(train);
        course.setDateService(DATE);
        course.setSens(SensCourse.ALLER);
        course.setDepartTheorique(DEPART);
        course.setArriveeTheorique(DEPART.plusMinutes(94));
        course.setAvancementKm(BigDecimal.ZERO);
        return course;
    }

    private static List<PassageGare> passages(Course course) {
        List<PassageGare> passages = new ArrayList<>();
        passages.add(passage(course, 1, 0, null, 0));
        passages.add(passage(course, 2, 50, 30, 32));
        passages.add(passage(course, 3, 100, 62, 64));
        passages.add(passage(course, 4, 150, 94, null));
        return passages;
    }

    private static PassageGare passage(Course course, int ordre, int pkKm,
                                       Integer offsetArrivee, Integer offsetDepart) {
        Gare gare = new Gare();
        gare.setId((long) ordre);
        gare.setNom("Gare " + ordre);

        PassageGare passage = new PassageGare();
        passage.setId(course.getId() * 100 + ordre);
        passage.setCourse(course);
        passage.setGare(gare);
        passage.setOrdre((short) ordre);
        passage.setPkKm(BigDecimal.valueOf(pkKm));
        passage.setMargeMin((short) 0);
        if (offsetArrivee != null) {
            passage.setArriveeTheorique(DEPART.plusMinutes(offsetArrivee));
            passage.setArriveeEstimee(DEPART.plusMinutes(offsetArrivee));
        }
        if (offsetDepart != null) {
            passage.setDepartTheorique(DEPART.plusMinutes(offsetDepart));
            passage.setDepartEstimee(DEPART.plusMinutes(offsetDepart));
        }
        return passage;
    }

    /** Wires the historiser over mocks holding the given courses. */
    private static HistorisateurJournee historisateurPour(List<Course> courses, List<PassageGare> passages) {
        GenerateurCourses generateur = mock(GenerateurCourses.class);
        CourseRepository courseRepository = mock(CourseRepository.class);
        PassageGareRepository passageRepository = mock(PassageGareRepository.class);

        when(generateur.genererPour(any())).thenReturn(0);
        when(courseRepository.findParDateService(DATE)).thenReturn(courses);
        when(passageRepository.findByCourseIds(anyCollection())).thenReturn(passages);

        return new HistorisateurJournee(generateur, courseRepository, passageRepository);
    }

    @Test
    @DisplayName("une course historisée est terminée et porte des heures réelles")
    void uneCourseHistoriseeEstTerminee() {
        Course course = course(1L);
        List<PassageGare> passages = passages(course);
        historisateurPour(List.of(course), passages).historiser(DATE);

        assertEquals(StatutCourse.TERMINUS_ATTEINT, course.getStatut());
        assertEquals(BigDecimal.valueOf(150), course.getAvancementKm());
        assertNotNull(course.getDernierePositionAt());

        // The origin has no arrival and the terminus no departure; every other
        // time is stamped. An estimate exists exactly where its theoretical
        // counterpart does -- chk_passage_estimee_suit_theorique.
        for (PassageGare passage : passages) {
            assertEquals(passage.getArriveeTheorique() == null, passage.getArriveeReelle() == null);
            assertEquals(passage.getArriveeTheorique() == null, passage.getArriveeEstimee() == null);
            assertEquals(passage.getDepartTheorique() == null, passage.getDepartReelle() == null);
            assertEquals(passage.getDepartTheorique() == null, passage.getDepartEstimee() == null);
        }
    }

    @Test
    @DisplayName("le retard de la course est celui de son terminus")
    void leRetardDeLaCourseEstCeluiDuTerminus() {
        Course course = course(1L);
        List<PassageGare> passages = passages(course);
        historisateurPour(List.of(course), passages).historiser(DATE);

        PassageGare terminus = passages.get(passages.size() - 1);
        assertEquals(terminus.getRetardMin(), course.getRetardMin());
        // A cause is recorded when, and only when, there is a delay to explain.
        if (course.getRetardMin() > 0) {
            assertNotNull(course.getCauseRetard());
        } else {
            assertNull(course.getCauseRetard());
        }
    }

    @Test
    @DisplayName("le retard ne décroît jamais le long du parcours")
    void leRetardEstMonotone() {
        // Across many trains so the assertion sees perturbed runs, not just the
        // ~72 % that finish on time.
        for (long trainId = 1; trainId <= 40; trainId++) {
            Course course = course(trainId);
            List<PassageGare> passages = passages(course);
            historisateurPour(List.of(course), passages).historiser(DATE);

            int precedent = 0;
            for (PassageGare passage : passages) {
                assertTrue(passage.getRetardMin() >= precedent,
                        "retard en recul sur la course " + trainId + " à l'ordre " + passage.getOrdre());
                precedent = passage.getRetardMin();
            }
        }
    }

    @Test
    @DisplayName("rejouer le backfill reproduit exactement les mêmes chiffres")
    void leBackfillEstDeterministe() {
        Course premiere = course(7L);
        List<PassageGare> passagesA = passages(premiere);
        historisateurPour(List.of(premiere), passagesA).historiser(DATE);

        // A second run over freshly built rows: same natural key, so the seed
        // and therefore every stamped value must come out identical. This is
        // what makes the tool idempotent without an "already done" flag.
        Course seconde = course(7L);
        List<PassageGare> passagesB = passages(seconde);
        historisateurPour(List.of(seconde), passagesB).historiser(DATE);

        assertEquals(premiere.getRetardMin(), seconde.getRetardMin());
        assertEquals(premiere.getCauseRetard(), seconde.getCauseRetard());
        assertEquals(premiere.getDernierePositionAt(), seconde.getDernierePositionAt());
        for (int i = 0; i < passagesA.size(); i++) {
            assertEquals(passagesA.get(i).getArriveeReelle(), passagesB.get(i).getArriveeReelle());
            assertEquals(passagesA.get(i).getDepartReelle(), passagesB.get(i).getDepartReelle());
            assertEquals(passagesA.get(i).getRetardMin(), passagesB.get(i).getRetardMin());
        }
    }

    @Test
    @DisplayName("une course annulée n'est pas transformée en course terminée")
    void uneCourseAnnuleeEstPreservee() {
        Course course = course(3L);
        course.setStatut(StatutCourse.ANNULE);
        List<PassageGare> passages = passages(course);
        historisateurPour(List.of(course), passages).historiser(DATE);

        assertEquals(StatutCourse.ANNULE, course.getStatut());
        for (PassageGare passage : passages) {
            assertNull(passage.getArriveeReelle());
            assertNull(passage.getDepartReelle());
        }
    }

    /**
     * The calibration check. Phase 2 measured 23-29 % of courses 5+ minutes late
     * against the live simulator; this restatement of the same model has to land
     * in that neighbourhood, or the two have drifted apart and the synthesised
     * history stops being comparable to a simulated day.
     */
    @Test
    @DisplayName("la part de courses en retard reste dans la fourchette mesurée en phase 2")
    void lapartDeRetardsResteCalibree() {
        int enRetard = 0;
        int total = 500;
        for (int i = 0; i < total; i++) {
            // Through aleatoire(), not new Random(i): consecutive raw seeds
            // give consecutive first outputs, and the first output is the
            // perturbation gate. That is the bug this test found.
            Random rnd = ModelePerturbation.aleatoire(i);
            List<ModelePerturbation.Incident> incidents = ModelePerturbation.tirer(rnd, 94);
            if (ModelePerturbation.perteCumulee(incidents, 94) >= 5 * 60) {
                enRetard++;
            }
        }
        double part = (double) enRetard / total;
        assertTrue(part > 0.15 && part < 0.40,
                "part de courses 5+ min en retard hors fourchette : " + part);
    }
}
