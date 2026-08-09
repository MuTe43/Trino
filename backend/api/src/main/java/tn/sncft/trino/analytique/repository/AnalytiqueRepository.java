package tn.sncft.trino.analytique.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tn.sncft.trino.analytique.dto.CaseHeatmapDTO;
import tn.sncft.trino.analytique.dto.Granularite;
import tn.sncft.trino.analytique.dto.PointPonctualiteDTO;
import tn.sncft.trino.analytique.dto.RetardParLigneDTO;

import java.time.LocalDate;
import java.util.List;

/**
 * The dashboard aggregates, as native SQL.
 *
 * <p>Native and not JPQL on purpose (phase-5 spec): these are {@code filter
 * (where ...)} aggregates, {@code date_trunc} groupings and a timezone
 * conversion, none of which JPQL expresses without a fight.
 *
 * <p>Two rules run through all of them:
 *
 * <ul>
 *   <li>Punctuality counts only stops that were actually reached
 *       ({@code arrivee_reelle is not null}). A stop still ahead of the train
 *       carries {@code retard_min = 0}, so counting it would score the whole
 *       untravelled remainder of the day as on time -- punctuality would start
 *       every morning at 100 % and fall as reality arrived.</li>
 *   <li>Hours are bucketed in {@code Africa/Tunis}. Times are stored as
 *       timestamptz in UTC (invariant 6), so grouping on the raw value would
 *       displace every bucket by the network's offset.</li>
 * </ul>
 */
@Repository
public class AnalytiqueRepository {

    /**
     * Under five minutes is A_L_HEURE in {@code domain-model.md}. The same cut
     * is used for "is this course late" and "was this stop served on time", so
     * the two never disagree on a dashboard that shows both.
     */
    private static final int SEUIL_RETARD_MIN = 5;

    private final JdbcTemplate jdbcTemplate;

    public AnalytiqueRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Course-level counters for one service date. */
    public CompteursJour compteursDuJour(LocalDate date) {
        String sql = """
                select
                  count(*) filter (where c.statut <> 'ANNULE')                        as trains,
                  count(*) filter (where c.statut <> 'ANNULE' and c.retard_min >= ?)  as retards,
                  coalesce(avg(c.retard_min) filter
                           (where c.statut <> 'ANNULE' and c.retard_min > 0), 0)      as retard_moyen,
                  count(*) filter (where c.statut = 'ANNULE')                         as annules,
                  coalesce(sum(t.capacite) filter
                           (where c.statut <> 'ANNULE' and c.retard_min >= ?), 0)     as voyageurs
                from course c
                  join train t on t.id = c.train_id
                where c.date_service = ?
                """;
        return jdbcTemplate.queryForObject(sql, (rs, ligne) -> new CompteursJour(
                rs.getLong("trains"),
                rs.getLong("retards"),
                rs.getDouble("retard_moyen"),
                rs.getLong("annules"),
                rs.getLong("voyageurs")), SEUIL_RETARD_MIN, SEUIL_RETARD_MIN, date);
    }

    /** Stop-level punctuality for one service date. */
    public CompteursPonctualite ponctualiteDuJour(LocalDate date) {
        String sql = """
                select count(*)                                    as mesures,
                       count(*) filter (where pg.retard_min < ?)   as ponctuels
                from passage_gare pg
                  join course c on c.id = pg.course_id
                where c.date_service = ?
                  and pg.arrivee_reelle is not null
                """;
        return jdbcTemplate.queryForObject(sql, (rs, ligne) -> new CompteursPonctualite(
                rs.getLong("mesures"),
                rs.getLong("ponctuels")), SEUIL_RETARD_MIN, date);
    }

    public List<RetardParLigneDTO> retardsParLigne(LocalDate date) {
        String sql = """
                select l.id                                                            as ligne_id,
                       l.nom                                                           as ligne_nom,
                       count(*) filter (where c.statut <> 'ANNULE')                    as courses,
                       count(*) filter (where c.statut <> 'ANNULE'
                                          and c.retard_min >= ?)                       as en_retard,
                       coalesce(avg(c.retard_min) filter
                                (where c.statut <> 'ANNULE' and c.retard_min > 0), 0)  as retard_moyen,
                       coalesce(max(c.retard_min) filter (where c.statut <> 'ANNULE'), 0) as retard_max
                from course c
                  join ligne l on l.id = c.ligne_id
                where c.date_service = ?
                group by l.id, l.nom
                order by l.nom
                """;
        return jdbcTemplate.query(sql, (rs, ligne) -> new RetardParLigneDTO(
                rs.getLong("ligne_id"),
                rs.getString("ligne_nom"),
                rs.getLong("courses"),
                rs.getLong("en_retard"),
                rs.getDouble("retard_moyen"),
                rs.getInt("retard_max")), SEUIL_RETARD_MIN, date);
    }

    public List<CaseHeatmapDTO> heatmap(LocalDate du, LocalDate au) {
        String sql = """
                select g.id                                                          as gare_id,
                       g.nom                                                         as gare_nom,
                       extract(hour from pg.arrivee_theorique at time zone 'Africa/Tunis')::int as heure,
                       avg(pg.retard_min)                                            as retard_moyen,
                       count(*)                                                      as passages
                from passage_gare pg
                  join course c on c.id = pg.course_id
                  join gare g   on g.id = pg.gare_id
                where c.date_service between ? and ?
                  and pg.arrivee_theorique is not null
                  and pg.arrivee_reelle is not null
                group by g.id, g.nom, heure
                order by g.nom, heure
                """;
        return jdbcTemplate.query(sql, (rs, ligne) -> new CaseHeatmapDTO(
                rs.getLong("gare_id"),
                rs.getString("gare_nom"),
                rs.getInt("heure"),
                rs.getDouble("retard_moyen"),
                rs.getLong("passages")), du, au);
    }

    public List<PointPonctualiteDTO> ponctualite(LocalDate du, LocalDate au, Granularite granularite) {
        // The date_trunc unit is an SQL identifier, so it is concatenated, not
        // bound. It comes from the Granularite enum and never from the query
        // string -- that is the whole reason the enum carries it.
        String sql = """
                select date_trunc('%s', c.date_service::timestamp)::date  as periode,
                       count(*)                                           as passages,
                       count(*) filter (where pg.retard_min < ?)          as ponctuels,
                       coalesce(avg(pg.retard_min), 0)                    as retard_moyen
                from passage_gare pg
                  join course c on c.id = pg.course_id
                where c.date_service between ? and ?
                  and pg.arrivee_reelle is not null
                group by periode
                order by periode
                """.formatted(granularite.uniteSql());
        return jdbcTemplate.query(sql, (rs, ligne) -> {
            long passages = rs.getLong("passages");
            long ponctuels = rs.getLong("ponctuels");
            return new PointPonctualiteDTO(
                    rs.getObject("periode", java.sql.Date.class).toLocalDate(),
                    passages,
                    ponctuels,
                    passages == 0 ? 0 : (double) ponctuels / passages,
                    rs.getDouble("retard_moyen"));
        }, SEUIL_RETARD_MIN, du, au);
    }

    /**
     * Courses per exact delay value over a range, for the histogram.
     *
     * <p>Grouped on the raw minute count rather than on a CASE expression that
     * would restate the bucket boundaries: the caller folds these into buckets
     * through {@code ClasseRetard.de}, so the thresholds stay defined once. The
     * result is bounded by the number of distinct delays, around a hundred
     * rows, not by the number of courses.
     */
    public List<CompteRetard> coursesParRetard(LocalDate du, LocalDate au) {
        String sql = """
                select c.retard_min as retard_min, count(*) as courses
                from course c
                where c.date_service between ? and ?
                  and c.statut <> 'ANNULE'
                group by c.retard_min
                """;
        return jdbcTemplate.query(sql, (rs, ligne) -> new CompteRetard(
                rs.getInt("retard_min"),
                rs.getLong("courses")), du, au);
    }

    public record CompteRetard(int retardMin, long courses) {
    }

    /** Course-level counters, before the incident figures phase 6 will add. */
    public record CompteursJour(long trains, long retards, double retardMoyenMin,
                                long annules, long voyageursImpactes) {
    }

    public record CompteursPonctualite(long mesures, long ponctuels) {

        public double taux() {
            return mesures == 0 ? 0 : (double) ponctuels / mesures;
        }
    }
}
