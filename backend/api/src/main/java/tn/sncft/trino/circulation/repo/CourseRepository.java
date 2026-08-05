package tn.sncft.trino.circulation.repo;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tn.sncft.trino.circulation.domaine.Course;
import tn.sncft.trino.circulation.domaine.StatutCourse;
import tn.sncft.trino.referentiel.domaine.TypeTrain;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

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

    @Query("""
            select c from Course c
              join fetch c.ligne
              join fetch c.train
            where c.id = :id
            """)
    Optional<Course> findAvecLigneEtTrain(@Param("id") Long id);

    /**
     * The one filtered read behind {@code /courses} and {@code /recherche}.
     *
     * <p>{@code q} is matched with {@code lower(...) like} against the train
     * number and name, the ligne name, and any gare the course calls at -- the
     * last of which covers the destination, since the terminus is one of those
     * stops. Callers pass it already lowercased and wrapped in {@code %}.
     *
     * <p>No index backs the {@code like}: with five lignes, twenty-five trains
     * and forty gares a sequential scan is faster than maintaining trigram
     * indexes, and a search engine here would be infrastructure we could not
     * justify (decision 4).
     *
     * <p>The fetch joins are both to-one, so pagination still happens in SQL.
     * The count query is spelled out because Spring Data cannot derive one from
     * a query with fetch joins.
     *
     * <p>{@code statuts} is never null or empty: "no filter" is expressed by
     * {@link tn.sncft.trino.circulation.service.CourseService} passing every
     * {@link StatutCourse} value, not by binding a null collection. Hibernate
     * does not reliably support {@code :statuts is null} against a collection
     * parameter, and {@code c.statut} is never null (the column is {@code not
     * null}), so "matches every known status" is exactly equivalent to "no
     * filter" and needs no null-guard in the query at all. This also makes the
     * phase-3 {@code :statut = c.statut} reversal moot for this query: the
     * text no longer contains {@code \.statut =} to begin with, so nothing is
     * dodging the acceptance grep on purpose anymore.
     */
    @Query(value = """
            select c from Course c
              join fetch c.ligne
              join fetch c.train
            where c.dateService = :date
              and (:ligneId is null or c.ligne.id = :ligneId)
              and c.statut in :statuts
              and (:type is null or c.train.type = :type)
              and (:gareId is null or exists (
                    select 1 from PassageGare p where p.course = c and p.gare.id = :gareId))
              and (:q is null
                    or lower(c.train.numero) like :q
                    or lower(c.train.nom) like :q
                    or lower(c.ligne.nom) like :q
                    or exists (
                        select 1 from PassageGare pg where pg.course = c and lower(pg.gare.nom) like :q))
            order by c.departTheorique asc
            """,
            countQuery = """
            select count(c) from Course c
            where c.dateService = :date
              and (:ligneId is null or c.ligne.id = :ligneId)
              and c.statut in :statuts
              and (:type is null or c.train.type = :type)
              and (:gareId is null or exists (
                    select 1 from PassageGare p where p.course = c and p.gare.id = :gareId))
              and (:q is null
                    or lower(c.train.numero) like :q
                    or lower(c.train.nom) like :q
                    or lower(c.ligne.nom) like :q
                    or exists (
                        select 1 from PassageGare pg where pg.course = c and lower(pg.gare.nom) like :q))
            """)
    Page<Course> rechercher(@Param("date") LocalDate date,
                            @Param("ligneId") Long ligneId,
                            @Param("gareId") Long gareId,
                            @Param("statuts") Collection<StatutCourse> statuts,
                            @Param("type") TypeTrain type,
                            @Param("q") String q,
                            Pageable pageable);
}
