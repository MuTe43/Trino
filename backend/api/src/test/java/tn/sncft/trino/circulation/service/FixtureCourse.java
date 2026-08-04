package tn.sncft.trino.circulation.service;

import tn.sncft.trino.circulation.domaine.Course;
import tn.sncft.trino.circulation.domaine.PassageGare;
import tn.sncft.trino.circulation.domaine.SensCourse;
import tn.sncft.trino.referentiel.domaine.Gare;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * A four-stop run used by the engine tests: 150 km, departing 06:00, arriving
 * 07:34, with padding on the last two segments so margin absorption has
 * something to absorb.
 *
 * <pre>
 *   ordre  pk    arrivée  départ  marge
 *   1        0      --     06:00    0
 *   2       50   06:30     06:32    0
 *   3      100   07:02     07:04    3
 *   4      150   07:34       --     2
 * </pre>
 */
final class FixtureCourse {

    static final OffsetDateTime DEPART = OffsetDateTime.of(
            2026, 8, 4, 6, 0, 0, 0, ZoneOffset.UTC);

    private FixtureCourse() {
    }

    static Course course() {
        Course course = new Course();
        course.setId(1L);
        course.setDateService(LocalDate.of(2026, 8, 4));
        course.setSens(SensCourse.ALLER);
        course.setDepartTheorique(DEPART);
        course.setArriveeTheorique(DEPART.plusMinutes(94));
        course.setAvancementKm(BigDecimal.ZERO);
        return course;
    }

    static List<PassageGare> passages(Course course) {
        return List.of(
                passage(course, 1, 0, null, 0, 0),
                passage(course, 2, 50, 30, 32, 0),
                passage(course, 3, 100, 62, 64, 3),
                passage(course, 4, 150, 94, null, 2));
    }

    private static PassageGare passage(Course course, int ordre, int pkKm,
                                       Integer offsetArrivee, Integer offsetDepart, int margeMin) {
        Gare gare = new Gare();
        gare.setId((long) ordre);
        gare.setNom("Gare " + ordre);

        PassageGare passage = new PassageGare();
        passage.setId((long) ordre);
        passage.setCourse(course);
        passage.setGare(gare);
        passage.setOrdre((short) ordre);
        passage.setPkKm(BigDecimal.valueOf(pkKm));
        passage.setMargeMin((short) margeMin);
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

    static PassageGare parOrdre(List<PassageGare> passages, int ordre) {
        return passages.stream()
                .filter(passage -> passage.getOrdre() == ordre)
                .findFirst()
                .orElseThrow();
    }
}
