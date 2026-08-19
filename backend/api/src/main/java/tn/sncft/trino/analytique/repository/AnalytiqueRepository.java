package tn.sncft.trino.analytique.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tn.sncft.trino.analytique.dto.CaseHeatmapDTO;
import tn.sncft.trino.analytique.dto.DisponibiliteTrainDTO;
import tn.sncft.trino.analytique.dto.Granularite;
import tn.sncft.trino.analytique.dto.PointPonctualiteDTO;
import tn.sncft.trino.analytique.dto.LigneIncidentsDTO;
import tn.sncft.trino.analytique.dto.RetardParGareDTO;
import tn.sncft.trino.analytique.dto.RetardParLigneDTO;
import tn.sncft.trino.exploitation.domaine.Gravite;
import tn.sncft.trino.exploitation.domaine.TypeIncident;

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

    /**
     * Incidents declared on one service date, split into still-open and
     * resolved.
     *
     * <p>Filed by the day the incident <b>happened</b>, not by whether it is
     * open right now: every other tile on that dashboard is a property of the
     * day itself, and counting currently-open incidents would make a KPI for a
     * past date change every time somebody closed an old one.
     *
     * <p>{@code at time zone 'Africa/Tunis'} for the same reason as the heatmap
     * (invariant 6). Without it, everything declared between midnight and 01:00
     * local is filed under the previous day -- an hour of every night landing on
     * the wrong date, silently.
     */
    public CompteursIncidents compteursIncidents(LocalDate date) {
        String sql = """
                select count(*) filter (where i.statut <> 'RESOLU') as ouverts,
                       count(*) filter (where i.statut = 'RESOLU')  as resolus
                from incident i
                where (i.survenu_at at time zone 'Africa/Tunis')::date = ?
                """;
        return jdbcTemplate.queryForObject(sql, (rs, ligne) -> new CompteursIncidents(
                rs.getLong("ouverts"),
                rs.getLong("resolus")), date);
    }

    /**
     * The incidents report: one row per type and gravité over a window, with the
     * mean time to resolution of the ones that were closed.
     *
     * <p>{@code delaiResolutionMoyenH} is null for a bucket where nothing has
     * been resolved yet -- an average over no rows, not a zero. Reported as 0 it
     * would read as "resolved instantly", which is the opposite of the truth.
     */
    public List<LigneIncidentsDTO> incidents(LocalDate du, LocalDate au) {
        String sql = """
                select i.type                                       as type,
                       i.gravite                                    as gravite,
                       count(*)                                     as total,
                       count(*) filter (where i.statut = 'RESOLU')  as resolus,
                       avg(extract(epoch from (i.resolu_at - i.survenu_at)) / 3600.0) as delai_moyen_h
                from incident i
                where (i.survenu_at at time zone 'Africa/Tunis')::date between ? and ?
                group by i.type, i.gravite
                order by count(*) desc, i.type, i.gravite
                """;
        return jdbcTemplate.query(sql, (rs, ligne) -> {
            double delai = rs.getDouble("delai_moyen_h");
            // Read IMMEDIATELY after the getDouble it refers to. wasNull()
            // reports on the last column read from this row, so testing it after
            // the two getLong calls below reports on `resolus` instead -- which
            // is never null, so every unresolved bucket came back as 0.0 h,
            // reading as "resolved instantly". Seen at runtime before this line
            // was hoisted.
            boolean aucuneResolution = rs.wasNull();
            return new LigneIncidentsDTO(
                    TypeIncident.valueOf(rs.getString("type")),
                    Gravite.valueOf(rs.getString("gravite")),
                    rs.getLong("total"),
                    rs.getLong("resolus"),
                    aucuneResolution ? null : delai);
        }, du, au);
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

    /**
     * The same profile over a range, for the {@code retards-par-ligne} report.
     *
     * <p>A separate method rather than a nullable second date on the dashboard
     * query: the dashboard asks about one service date and the report asks about
     * a window, and collapsing them would put a branch in the middle of an
     * aggregate that both of them read.
     */
    public List<RetardParLigneDTO> retardsParLigne(LocalDate du, LocalDate au) {
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
                where c.date_service between ? and ?
                group by l.id, l.nom
                order by l.nom
                """;
        return jdbcTemplate.query(sql, (rs, ligne) -> new RetardParLigneDTO(
                rs.getLong("ligne_id"),
                rs.getString("ligne_nom"),
                rs.getLong("courses"),
                rs.getLong("en_retard"),
                rs.getDouble("retard_moyen"),
                rs.getInt("retard_max")), SEUIL_RETARD_MIN, du, au);
    }

    /**
     * Delay by gare over a range: the heatmap aggregate without the hour
     * dimension.
     *
     * <p>Carries the same two filters as the heatmap and for the same reasons.
     * {@code arrivee_reelle is not null} keeps stops the train has not reached
     * out of the average -- they hold {@code retard_min = 0} and would score the
     * untravelled rest of the day as on time. {@code arrivee_theorique is not
     * null} drops an origin, which has a departure but no arrival, and whose
     * delay is therefore not a measurement of this station's punctuality.
     *
     * <p>Ordered worst first: the reason to open this report is to find out
     * which stations are the problem, and a report sorted alphabetically makes
     * the reader do that work in the spreadsheet.
     */
    public List<RetardParGareDTO> retardsParGare(LocalDate du, LocalDate au) {
        String sql = """
                select g.id                                          as gare_id,
                       g.nom                                         as gare_nom,
                       g.region                                      as region,
                       count(*)                                      as passages,
                       count(*) filter (where pg.retard_min >= ?)    as en_retard,
                       coalesce(avg(pg.retard_min), 0)               as retard_moyen,
                       coalesce(max(pg.retard_min), 0)               as retard_max
                from passage_gare pg
                  join course c on c.id = pg.course_id
                  join gare g   on g.id = pg.gare_id
                where c.date_service between ? and ?
                  and pg.arrivee_theorique is not null
                  and pg.arrivee_reelle is not null
                group by g.id, g.nom, g.region
                order by coalesce(avg(pg.retard_min), 0) desc, g.nom
                """;
        return jdbcTemplate.query(sql, (rs, ligne) -> new RetardParGareDTO(
                rs.getLong("gare_id"),
                rs.getString("gare_nom"),
                rs.getString("region"),
                rs.getLong("passages"),
                rs.getLong("en_retard"),
                rs.getDouble("retard_moyen"),
                rs.getInt("retard_max")), SEUIL_RETARD_MIN, du, au);
    }

    /**
     * Share of each train's scheduled courses that actually ran, per ligne, over
     * a range.
     *
     * <p>Computed entirely from {@code course} because that is the only place
     * the information exists: a {@code Train} carries no status and no downtime
     * (invariant 1), so "availability" can only mean "its programme ran". The
     * denominator is every course scheduled for the train in the window,
     * cancelled ones included -- which is what makes the ratio mean anything.
     *
     * <p>Ordered worst first, then by train, so the trains that lost runs are at
     * the top of the export rather than scattered through it.
     */
    public List<DisponibiliteTrainDTO> disponibiliteTrains(LocalDate du, LocalDate au) {
        String sql = """
                select t.numero                                     as numero,
                       t.nom                                        as nom,
                       l.nom                                        as ligne_nom,
                       count(*)                                     as programmees,
                       count(*) filter (where c.statut <> 'ANNULE') as realisees,
                       count(*) filter (where c.statut =  'ANNULE') as annulees
                from course c
                  join train t on t.id = c.train_id
                  join ligne l on l.id = c.ligne_id
                where c.date_service between ? and ?
                group by t.id, t.numero, t.nom, l.id, l.nom
                order by count(*) filter (where c.statut = 'ANNULE') desc, t.numero, l.nom
                """;
        return jdbcTemplate.query(sql, (rs, ligne) -> {
            long programmees = rs.getLong("programmees");
            long realisees = rs.getLong("realisees");
            return new DisponibiliteTrainDTO(
                    rs.getString("numero"),
                    rs.getString("nom"),
                    rs.getString("ligne_nom"),
                    programmees,
                    realisees,
                    rs.getLong("annulees"),
                    // group by guarantees at least one row, so this cannot divide
                    // by zero -- but the guard states that rather than leaving the
                    // reader to reconstruct it.
                    programmees == 0 ? 0 : (double) realisees / programmees);
        }, du, au);
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

    /** Incidents declared on a service date, by whether they are still open. */
    public record CompteursIncidents(long ouverts, long resolus) {
    }

    public record CompteursPonctualite(long mesures, long ponctuels) {

        public double taux() {
            return mesures == 0 ? 0 : (double) ponctuels / mesures;
        }
    }
}
