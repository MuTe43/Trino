package tn.sncft.trino.circulation.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.sncft.trino.circulation.domaine.StatutCourse;
import tn.sncft.trino.circulation.dto.PassageDTO;
import tn.sncft.trino.circulation.repo.PassageGareRepository;
import tn.sncft.trino.commun.PageableUtils;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

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

    /** A cancelled or finished run has no departure left to advertise. */
    private static final Set<StatutCourse> STATUTS_CLOS =
            EnumSet.of(StatutCourse.ANNULE, StatutCourse.TERMINUS_ATTEINT);

    private final PassageGareRepository passageGareRepository;
    private final HorlogeCirculation horloge;

    public DepartsService(PassageGareRepository passageGareRepository, HorlogeCirculation horloge) {
        this.passageGareRepository = passageGareRepository;
        this.horloge = horloge;
    }

    @Transactional(readOnly = true)
    public List<PassageDTO> prochainsDeparts(Long gareId, int limite) {
        OffsetDateTime maintenant = horloge.maintenant();
        return passageGareRepository.findDeparts(
                        gareId,
                        LocalDate.now(ZONE_RESEAU),
                        maintenant,
                        STATUTS_CLOS,
                        PageableUtils.de(0, limite))
                .stream()
                .map(CourseService::versPassage)
                .toList();
    }
}
