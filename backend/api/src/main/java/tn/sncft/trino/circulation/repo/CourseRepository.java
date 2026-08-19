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

    /**
     * Every course of a service date, whatever its status. Used by the history
     * backfill, which stamps finished runs and therefore must see the ones it
     * already marked TERMINUS_ATTEINT on a previous run.
     */
    @Query("""
            select c from Course c
              join fetch c.ligne
              join fetch c.train
            where c.dateService = :date
            order by c.departTheorique asc
            """)
    List<Course> findParDateService(@Param("date") LocalDate date);

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
    @Query(value = SELECTION + CRITERES + " order by c.departTheorique asc",
            countQuery = COMPTAGE + CRITERES)
    Page<Course> rechercher(@Param("date") LocalDate date,
                            @Param("ligneId") Long ligneId,
                            @Param("gareId") Long gareId,
                            @Param("statuts") Collection<StatutCourse> statuts,
                            @Param("type") TypeTrain type,
                            @Param("q") String q,
                            @Param("region") String region,
                            @Param("destination") String destination,
                            @Param("departMin") OffsetDateTime departMin,
                            @Param("departMax") OffsetDateTime departMax,
                            Pageable pageable);

    String SELECTION = """
            select c from Course c
              join fetch c.ligne
              join fetch c.train
            """;

    String COMPTAGE = "select count(c) from Course c\n";

    /**
     * The predicate block, written once and concatenated into both the page
     * query and its count.
     *
     * <p>It used to be spelled out twice. Phase 9 added four criteria to it, and
     * four edits applied to one copy and not the other is a search that returns a
     * page whose {@code total} disagrees with it — visible only as pagination
     * that runs out early. Both strings are compile-time constants, so this is
     * still one literal query as far as Spring Data is concerned.
     *
     * <p>{@code region} and {@code destination} are like-patterns built by the
     * service exactly as {@code q} is: lowercased and wrapped in {@code %}, so a
     * user typing "Gab" finds Gabès.
     *
     * <p>{@code departMin}/{@code departMax} are instants, not times of day. The
     * caller resolves the requested wall-clock window against the service date in
     * Africa/Tunis before binding it — comparing a stored timestamptz against a
     * bare {@code time} would either need {@code at time zone} (which JPQL does
     * not express) or would silently compare UTC hours against local ones and
     * shift every result by the network's offset (invariant 6).
     *
     * <p>They are also the two parameters with no {@code is null} guard, and that
     * is not an oversight. Postgres cannot infer a type for a parameter that
     * appears only in {@code ? is null}, and the whole query fails with
     * {@code could not determine data type of parameter} — a 500 on every call to
     * {@code /recherche}, including calls that pass no time window at all. The
     * caller therefore always binds a real window, defaulting to the bounds of
     * the service date, which the {@code dateService} predicate has already
     * restricted the rows to. Found at runtime in phase 9; a
     * {@code CritèresRechercheTest} case now pins each criterion against a live
     * database, because nothing about this is visible to the compiler.
     */
    String CRITERES = """
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
              and (:region is null or exists (
                    select 1 from PassageGare pr where pr.course = c and lower(pr.gare.region) like :region))
              and (:destination is null or exists (
                    select 1 from PassageGare pd
                    where pd.course = c
                      and lower(pd.gare.nom) like :destination
                      and pd.ordre = (select max(pm.ordre) from PassageGare pm where pm.course = c)))
              and c.departTheorique >= :departMin
              and c.departTheorique <= :departMax
            """;
}
