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
     */
    @Query("""
            select p from PassageGare p
              join fetch p.gare
              join fetch p.course c
              join fetch c.ligne
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
}
