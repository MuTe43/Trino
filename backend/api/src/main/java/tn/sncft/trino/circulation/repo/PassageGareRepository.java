package tn.sncft.trino.circulation.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tn.sncft.trino.circulation.domaine.PassageGare;

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
}
