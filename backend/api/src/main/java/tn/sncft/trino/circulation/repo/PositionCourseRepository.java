package tn.sncft.trino.circulation.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tn.sncft.trino.circulation.domaine.PositionCourse;

import java.time.OffsetDateTime;
import java.util.List;

public interface PositionCourseRepository extends JpaRepository<PositionCourse, Long> {

    /**
     * Ping history for the trace replay. This is the only read of this table
     * and it is scoped to one course -- it is never touched on the hot path.
     *
     * <p>The two gare joins are {@code left join fetch} because either end can
     * be null (before the origin, after the terminus) and because the DTO wants
     * their ids without a lazy proxy load per row.
     */
    @Query("""
            select p from PositionCourse p
              left join fetch p.garePrecedente
              left join fetch p.gareSuivante
            where p.course.id = :courseId
              and (:depuis is null or p.horodatage >= :depuis)
            order by p.horodatage asc
            """)
    List<PositionCourse> findHistorique(@Param("courseId") Long courseId,
                                        @Param("depuis") OffsetDateTime depuis);
}
