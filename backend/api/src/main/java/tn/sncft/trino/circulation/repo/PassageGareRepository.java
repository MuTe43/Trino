package tn.sncft.trino.circulation.repo;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tn.sncft.trino.circulation.domaine.PassageGare;
import tn.sncft.trino.circulation.domaine.StatutCourse;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

public interface PassageGareRepository extends JpaRepository<PassageGare, Long> {

    /**
     * Batched on purpose: loading the stops of every course of the day one
     * course at a time is the obvious N+1 on the courses-du-jour payload.
     */
    @Query("""
            select p from PassageGare p
              join fetch p.gare
            where p.course.id in :courseIds
            order by p.course.id asc, p.ordre asc
            """)
    List<PassageGare> findByCourseIds(@Param("courseIds") Collection<Long> courseIds);

    @Query("""
            select p from PassageGare p
              join fetch p.gare
            where p.course.id = :courseId
            order by p.ordre asc
            """)
    List<PassageGare> findByCourseId(@Param("courseId") Long courseId);

    /**
     * The station board. Ordered by {@code departEstimee}, not by
     * {@code departTheorique}: a delayed train has to fall down the board, or
     * the board is lying to the people standing in front of it.
     *
     * <p>The terminus of a run has no departure and so never appears here.
     * {@code statutsExclus} excludes closed runs only ({@code TERMINUS_ATTEINT});
     * an {@code ANNULE} course is deliberately not excluded here -- the board
     * still renders it, as a present-but-dead row.
     *
     * <p>{@code join fetch c.train} is what lets the caller build a
     * {@code DepartGareDTO} (train number, name, type) without a second query
     * per row.
     */
    @Query("""
            select p from PassageGare p
              join fetch p.gare
              join fetch p.course c
              join fetch c.train
            where p.gare.id = :gareId
              and c.dateService = :date
              and p.departEstimee is not null
              and p.departEstimee >= :depuis
              and c.statut not in :statutsExclus
            order by p.departEstimee asc
            """)
    List<PassageGare> findDeparts(@Param("gareId") Long gareId,
                                  @Param("date") LocalDate date,
                                  @Param("depuis") OffsetDateTime depuis,
                                  @Param("statutsExclus") Collection<StatutCourse> statutsExclus,
                                  Pageable pageable);

    /**
     * Terminus gare name for each course, resolved as the stop with the
     * highest {@code ordre} for that course. One query for the whole batch
     * (a correlated subquery, evaluated by the database) rather than one
     * query per row -- the station board must not fetch a stop list per train
     * just to find out where it is headed.
     */
    @Query("""
            select p.course.id as courseId, p.gare.nom as nom
            from PassageGare p
            where p.course.id in :courseIds
              and p.ordre = (
                  select max(p2.ordre) from PassageGare p2 where p2.course.id = p.course.id
              )
            """)
    List<TerminusProjection> findTerminusGares(@Param("courseIds") Collection<Long> courseIds);

    interface TerminusProjection {
        Long getCourseId();

        String getNom();
    }
}
