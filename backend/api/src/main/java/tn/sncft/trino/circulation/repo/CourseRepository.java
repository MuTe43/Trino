package tn.sncft.trino.circulation.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tn.sncft.trino.circulation.domaine.Course;
import tn.sncft.trino.circulation.domaine.StatutCourse;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

public interface CourseRepository extends JpaRepository<Course, Long> {

    /**
     * The natural keys already materialised for a service date, loaded in one
     * query so GenerateurCourses can skip existing slots without probing the
     * database once per slot. Mirrors the unique constraint
     * (train_id, date_service, depart_theorique).
     */
    @Query("select c.train.id as trainId, c.departTheorique as departTheorique "
            + "from Course c where c.dateService = :date")
    List<CleCourse> findClesByDateService(@Param("date") LocalDate date);

    /** Projection for {@link #findClesByDateService}. */
    interface CleCourse {
        Long getTrainId();

        OffsetDateTime getDepartTheorique();
    }

    @Query("""
            select c from Course c
              join fetch c.ligne
              join fetch c.train
            where c.dateService = :date and c.statut not in :statutsExclus
            order by c.departTheorique asc
            """)
    List<Course> findDuJourSaufStatuts(@Param("date") LocalDate date,
                                       @Param("statutsExclus") Collection<StatutCourse> statutsExclus);

    @Query("""
            select c from Course c
              join fetch c.ligne
              join fetch c.train
            where c.id in :ids
            """)
    List<Course> findAvecLigneEtTrain(@Param("ids") Collection<Long> ids);
}
