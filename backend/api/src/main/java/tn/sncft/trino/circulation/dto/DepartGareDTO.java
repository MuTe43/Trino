package tn.sncft.trino.circulation.dto;

import tn.sncft.trino.circulation.domaine.ClasseRetard;
import tn.sncft.trino.circulation.domaine.StatutCourse;
import tn.sncft.trino.referentiel.domaine.TypeTrain;

import java.time.OffsetDateTime;

/**
 * One row of the station board. Unlike {@link PassageDTO}, which is a stop
 * with no train identity, this carries what a board actually needs: the train
 * number, its name, and where it is headed.
 *
 * <p>{@code destination} is resolved server-side from the course's last
 * {@code passage_gare} -- the board must never fetch a stop list per train to
 * find out where a run terminates.
 *
 * <p>{@code statut}, {@code retardMin} and {@code classeRetard} come from the
 * {@code course}; {@code quai} and the three departure times come from the
 * {@code passage_gare} at this gare. A cancelled course is still returned: the
 * board renders it as a present-but-dead row rather than making it vanish.
 */
public record DepartGareDTO(
        Long courseId,
        String numeroTrain,
        String nomTrain,
        TypeTrain type,
        String destination,
        String quai,
        OffsetDateTime departTheorique,
        OffsetDateTime departEstime,
        OffsetDateTime departReel,
        StatutCourse statut,
        int retardMin,
        ClasseRetard classeRetard) {
}
