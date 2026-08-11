package tn.sncft.trino.referentiel;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tn.sncft.trino.support.BaseDeDonneesTest;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the seed against regressing into a shape that produces
 * plausible-looking but wrong punctuality numbers.
 *
 * <p>The delay engine walks stops assuming {@code ordre} and {@code pk_km}
 * ascend together, and the ETA divides by a segment length. Violations do not
 * crash anything -- they quietly yield the wrong answer, which is why they are
 * asserted here rather than left to be noticed on a dashboard.
 *
 * <p>Runs against the local dev database, and <b>fails</b> rather than skips
 * when none is reachable -- see {@link BaseDeDonneesTest}. A seed regression
 * that slips through because nobody noticed the suite skipped it is the failure
 * mode this class exists to prevent.
 */
class CoherenceSeedTest {

    @BeforeAll
    static void verifierBase() {
        boolean seedCharge;
        // Reachability and content are reported apart on purpose: "no database"
        // and "a database with no seed in it" have different fixes, and the
        // second is what a TRINO_DB_URL aimed at another Postgres on the same
        // host looks like.
        try (Connection connexion = ouvrir();
             Statement statement = connexion.createStatement();
             ResultSet resultat = statement.executeQuery("select count(*) from desserte")) {
            seedCharge = resultat.next() && resultat.getInt(1) > 0;
        } catch (SQLException e) {
            BaseDeDonneesTest.exiger(false, "base de développement injoignable (" + e.getMessage() + ")");
            return;
        }
        BaseDeDonneesTest.exiger(seedCharge, "base joignable mais desserte est vide : migrations non appliquées");
    }

    private static Connection ouvrir() throws SQLException {
        return BaseDeDonneesTest.ouvrir();
    }

    @Test
    @DisplayName("ordre et pk_km progressent ensemble sur chaque ligne")
    void ordreEtPkProgressentEnsemble() {
        assertAucuneLigne("""
                select d.ligne_id, d.ordre, d.pk_km
                from desserte d
                where exists (
                  select 1 from desserte d2
                  where d2.ligne_id = d.ligne_id and d2.ordre < d.ordre and d2.pk_km >= d.pk_km
                )
                """, "des arrêts sont ordonnés à l'envers de leur chaînage");
    }

    @Test
    @DisplayName("deux arrêts d'une ligne ne partagent pas le même point")
    void pasDeSegmentDeLongueurNulle() {
        assertAucuneLigne("""
                select ligne_id
                from desserte d join gare g on g.id = d.gare_id
                group by ligne_id
                having count(*) <> count(distinct (g.latitude || ',' || g.longitude))
                """, "un segment de longueur nulle casse le diviseur de l'ETA");
    }

    @Test
    @DisplayName("aucun pk_km ne dépasse la longueur de sa ligne")
    void pkTientDansLaLigne() {
        assertAucuneLigne("""
                select d.ligne_id, max(d.pk_km), l.distance_km
                from desserte d join ligne l on l.id = d.ligne_id
                group by d.ligne_id, l.distance_km having max(d.pk_km) > l.distance_km
                """, "un arrêt est situé au-delà du terminus de sa ligne");
    }

    @Test
    @DisplayName("aucun train n'est plus rapide que sa ligne")
    void trainPasPlusRapideQueSaLigne() {
        assertAucuneLigne("""
                select t.numero, t.vitesse_max_kmh, l.vitesse_max_kmh
                from train t join ligne l on l.id = t.ligne_id
                where t.vitesse_max_kmh > l.vitesse_max_kmh
                """, "un train circulerait plus vite que sa ligne ne l'autorise");
    }

    @Test
    @DisplayName("chaque segment est parcourable par le train le plus lent de la ligne")
    void horaireRealisableParLeTrainLePlusLent() {
        // Not one of the four required checks, but the same class of defect:
        // one that yields plausible-looking, wrong punctuality rather than an
        // error. Measured against the SLOWEST train rostered on the ligne, not
        // the ligne limit -- a segment timed at exactly the line maximum is
        // still unrunnable by the freight service sharing the line, and those
        // trains would report a permanent delay no engine tuning explains.
        assertAucuneLigne("""
                select l.code, d.ordre, d.pk_km,
                       round((d.pk_km - p.pk_km)
                             / ((d.offset_arrivee_min - p.offset_depart_min) / 60.0), 1) as kmh_exige,
                       least(l.vitesse_max_kmh, lent.vitesse_min) as kmh_disponible
                from desserte d
                  join ligne l on l.id = d.ligne_id
                  join desserte p on p.ligne_id = d.ligne_id and p.ordre = d.ordre - 1
                  join (select ligne_id, min(vitesse_max_kmh) as vitesse_min
                        from train where actif and ligne_id is not null
                        group by ligne_id) lent on lent.ligne_id = l.id
                where d.offset_arrivee_min is not null
                  and p.offset_depart_min is not null
                  and d.offset_arrivee_min > p.offset_depart_min
                  and (d.pk_km - p.pk_km) / ((d.offset_arrivee_min - p.offset_depart_min) / 60.0)
                      > least(l.vitesse_max_kmh, lent.vitesse_min)
                """, "l'horaire exige plus que ce que le train le plus lent de la ligne peut tenir");
    }

    @Test
    @DisplayName("le dernier offset de chaque ligne correspond à son temps théorique")
    void tempsTheoriqueCoherentAvecLesOffsets() {
        assertAucuneLigne("""
                select l.code, l.temps_theorique_min, max(d.offset_arrivee_min) as dernier_offset
                from ligne l join desserte d on d.ligne_id = l.id
                group by l.code, l.temps_theorique_min
                having max(d.offset_arrivee_min) <> l.temps_theorique_min
                """, "temps_theorique_min ne correspond pas à l'arrivée au terminus");
    }

    private void assertAucuneLigne(String requete, String explication) {
        // No guard here: verifierBase() has already failed the whole class if
        // the database was missing. A per-test skip is what let a vacuous pass
        // look like a real one.
        List<String> violations = new ArrayList<>();
        try (Connection connexion = ouvrir();
             Statement statement = connexion.createStatement();
             ResultSet resultat = statement.executeQuery(requete)) {
            int colonnes = resultat.getMetaData().getColumnCount();
            while (resultat.next()) {
                StringBuilder ligne = new StringBuilder();
                for (int i = 1; i <= colonnes; i++) {
                    ligne.append(resultat.getMetaData().getColumnLabel(i))
                            .append('=').append(resultat.getString(i)).append(' ');
                }
                violations.add(ligne.toString().trim());
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Contrôle de cohérence impossible : " + e.getMessage(), e);
        }

        assertTrue(violations.isEmpty(),
                explication + " -- " + violations.size() + " violation(s) : " + violations);
    }
}
