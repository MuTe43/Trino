package tn.sncft.trino.circulation.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.sncft.trino.circulation.domaine.ClasseRetard;
import tn.sncft.trino.circulation.domaine.Course;
import tn.sncft.trino.circulation.domaine.PassageGare;
import tn.sncft.trino.circulation.domaine.StatutCourse;
import tn.sncft.trino.circulation.dto.DepartGareDTO;
import tn.sncft.trino.circulation.repo.PassageGareRepository;
import tn.sncft.trino.commun.PageableUtils;
import tn.sncft.trino.referentiel.domaine.Train;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The station board: what leaves this gare next.
 *
 * <p>Ordered by {@code departEstimee}, never by {@code departTheorique}. A
 * train running 40 minutes late has to fall below the ones now leaving before
 * it, otherwise the board contradicts the platform.
 *
 * <p>"Now" comes from {@link HorlogeCirculation} rather than the system clock,
 * so the board stays consistent with the estimates the engine wrote when the
 * feed is running on an accelerated clock.
 */
@Service
public class DepartsService {

    private static final ZoneId ZONE_RESEAU = ZoneId.of("Africa/Tunis");

    /**
     * A finished run has no departure left to advertise. A cancelled one is
     * deliberately NOT in this set: the board still renders it, as a
     * present-but-dead row, so passengers standing in front of it see that
     * their train was cancelled rather than seeing nothing at all.
     */
    private static final Set<StatutCourse> STATUTS_CLOS =
            EnumSet.of(StatutCourse.TERMINUS_ATTEINT);

    private final PassageGareRepository passageGareRepository;
    private final HorlogeCirculation horloge;

    public DepartsService(PassageGareRepository passageGareRepository, HorlogeCirculation horloge) {
        this.passageGareRepository = passageGareRepository;
        this.horloge = horloge;
    }

    @Transactional(readOnly = true)
    public List<DepartGareDTO> prochainsDeparts(Long gareId, int limite) {
        OffsetDateTime maintenant = horloge.maintenant();
        List<PassageGare> departs = passageGareRepository.findDeparts(
                gareId,
                LocalDate.now(ZONE_RESEAU),
                maintenant,
                STATUTS_CLOS,
                PageableUtils.de(0, limite));

        List<Long> courseIds = departs.stream()
                .map(passage -> passage.getCourse().getId())
                .distinct()
                .toList();
        Map<Long, String> destinations = courseIds.isEmpty()
                ? Map.of()
                : passageGareRepository.findTerminusGares(courseIds).stream()
                        .collect(Collectors.toMap(
                                PassageGareRepository.TerminusProjection::getCourseId,
                                PassageGareRepository.TerminusProjection::getNom));

        return departs.stream()
                .map(passage -> versDepart(passage, destinations.get(passage.getCourse().getId())))
                .toList();
    }

    private static DepartGareDTO versDepart(PassageGare passage, String destination) {
        Course course = passage.getCourse();
        Train train = course.getTrain();
        return new DepartGareDTO(
                course.getId(),
                train.getNumero(),
                train.getNom(),
                train.getType(),
                destination,
                passage.getQuai(),
                passage.getDepartTheorique(),
                passage.getDepartEstimee(),
                passage.getDepartReelle(),
                course.getStatut(),
                course.getRetardMin(),
                ClasseRetard.de(course.getRetardMin()));
    }
}
