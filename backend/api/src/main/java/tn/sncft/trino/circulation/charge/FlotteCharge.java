package tn.sncft.trino.circulation.charge;

import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.sncft.trino.circulation.domaine.Horaire;
import tn.sncft.trino.circulation.domaine.SensCourse;
import tn.sncft.trino.referentiel.domaine.Ligne;
import tn.sncft.trino.referentiel.domaine.Train;
import tn.sncft.trino.referentiel.domaine.TypeTrain;

import java.time.LocalTime;
import java.util.List;

/**
 * Writes and removes the synthetic fleet the load profile runs against.
 *
 * <p>The seed materialises 80 courses a day. The cahier des charges asks the
 * architecture to hold <em>plusieurs centaines de trains simultanément</em>, and
 * that claim was an argument rather than a measurement until this existed. This
 * class adds enough rolling stock and enough timetable slots that
 * {@code GenerateurCourses} materialises several hundred runs whose journeys
 * overlap, so the peak is concurrency and not merely daily volume.
 *
 * <p>Nothing here duplicates generation logic: it writes {@code train} and
 * {@code horaire} rows only, and {@link tn.sncft.trino.circulation.service.GenerateurCourses}
 * turns them into courses and passages exactly as it does for the seeded
 * timetable. A load profile that built its own courses would be measuring a code
 * path the product does not use.
 *
 * <p>Every row it writes carries the {@link #PREFIXE_NUMERO} marker on
 * {@code train.numero}, which is what makes removal exact. Nothing else in the
 * schema uses that prefix.
 *
 * <p>Deliberately not a Flyway migration. Migrations are immutable and apply
 * everywhere (invariant 4); a fleet that exists to be measured and then deleted
 * must never ship to a demo database, let alone a real one.
 *
 * <p>{@link EntityManager} rather than the référentiel repositories: this is a
 * fixture writer, not domain logic, and reaching into another package's
 * repositories to write test data is exactly the coupling decision 1 forbids.
 * Creation goes through the JPA entities so the mapping stays authoritative;
 * removal is native SQL because it is a bulk delete across four tables.
 */
@Service
public class FlotteCharge {

    private static final Logger log = LoggerFactory.getLogger(FlotteCharge.class);

    /**
     * Marker on {@code train.numero}. Every synthetic row starts with it and no
     * seeded row does, so {@link #supprimer()} can be an exact match rather than
     * a guess at an id range.
     */
    public static final String PREFIXE_NUMERO = "CHG-";

    /** SQL LIKE pattern for the marker. Contains no wildcard of its own. */
    private static final String MOTIF_NUMERO = PREFIXE_NUMERO + "%";

    /**
     * FRET is left out. It is the one type with no passenger-facing meaning, and
     * a load profile whose fleet is a quarter freight would misrepresent the
     * traffic mix the dashboards then aggregate.
     */
    private static final TypeTrain[] TYPES = {
            TypeTrain.EXPRESS, TypeTrain.BANLIEUE, TypeTrain.GRANDES_LIGNES
    };

    private final EntityManager em;

    public FlotteCharge(EntityManager em) {
        this.em = em;
    }

    /**
     * Creates {@code nombre} trains, one timetable slot each, departures spread
     * evenly over {@code etalementMin} minutes from {@code premierDepart}.
     *
     * <p>One slot per train rather than several slots on fewer trains: the
     * requirement is worded in trains, and a timetable that ran the same train
     * twice at once would be measuring a fleet the domain does not admit.
     *
     * <p>The spread is what produces concurrency. The shortest desserte on the
     * network is 35 minutes, so departures inside a window narrower than that are
     * all still running when the last one leaves.
     *
     * @return the number of trains created
     */
    @Transactional
    public int creer(int nombre, LocalTime premierDepart, int etalementMin) {
        if (nombre < 1) {
            throw new IllegalArgumentException("--trains doit être au moins 1, reçu " + nombre);
        }
        if (etalementMin < 0) {
            throw new IllegalArgumentException("--etalement ne peut pas être négatif, reçu " + etalementMin);
        }

        List<LigneCible> lignes = lignesUtilisables();
        if (lignes.isEmpty()) {
            throw new IllegalStateException(
                    "Aucune ligne active avec un tracé exploitable : le profil de charge n'a rien à faire circuler.");
        }

        // Spread over the closed interval, so the first departure is at
        // premierDepart and the last exactly etalementMin later. Seconds rather
        // than minutes: at 320 trains over 20 minutes a minute-resolution spread
        // would pile sixteen departures onto each of twenty timestamps.
        long etalementSec = etalementMin * 60L;
        int intervalles = Math.max(1, nombre - 1);

        for (int i = 0; i < nombre; i++) {
            LigneCible cible = lignes.get(i % lignes.size());
            Ligne ligne = em.getReference(Ligne.class, cible.id());

            Train train = new Train();
            train.setNumero(PREFIXE_NUMERO + String.format("%04d", i + 1));
            train.setNom("Charge " + (i + 1));
            train.setType(TYPES[i % TYPES.length]);
            train.setLigne(ligne);
            train.setCapacite((short) 300);
            // The ligne's own limit, not a constant. CoherenceSeedTest asserts
            // that no train is faster than the ligne it is assigned to, and a
            // fixture that ignored that turned the whole suite red the moment
            // the load profile was left in the database -- 320 violations from
            // one hardcoded 140. A fixture is not exempt from the invariants
            // the fixture's own schema is checked against.
            train.setVitesseMaxKmh(cible.vitesseMaxKmh());
            train.setActif(true);
            em.persist(train);

            Horaire horaire = new Horaire();
            horaire.setLigne(ligne);
            horaire.setTrain(train);
            // Alternating, so the load is not one-directional: a RETOUR course
            // walks the desserte mirrored and exercises the other half of
            // GenerateurCourses.planifier.
            horaire.setSens(i % 2 == 0 ? SensCourse.ALLER : SensCourse.RETOUR);
            horaire.setHeureDepart(premierDepart.plusSeconds(i * etalementSec / intervalles));
            horaire.setActif(true);
            em.persist(horaire);
        }

        log.info("{} train(s) et horaire(s) de charge créés, départs de {} à {} sur {} ligne(s).",
                nombre, premierDepart, premierDepart.plusSeconds(etalementSec), lignes.size());
        return nombre;
    }

    /**
     * Removes every synthetic train and everything derived from it.
     *
     * <p>Order is load-bearing. {@code passage_gare} and {@code position_course}
     * cascade from {@code course}, but {@code notification} and {@code incident}
     * reference it with NO ACTION, and {@code course} references {@code train}
     * the same way — so a delete that started at {@code train} would fail on a
     * constraint rather than tidy up.
     *
     * @return the number of trains removed
     */
    @Transactional
    public int supprimer() {
        String coursesDeCharge =
                "select c.id from course c join train t on t.id = c.train_id where t.numero like :motif";
        String trainsDeCharge = "select id from train where numero like :motif";

        executer("delete from notification where course_id in (" + coursesDeCharge + ")");
        executer("delete from incident where course_id in (" + coursesDeCharge + ")");
        executer("delete from course where train_id in (" + trainsDeCharge + ")");
        executer("delete from horaire where train_id in (" + trainsDeCharge + ")");
        int trains = executer("delete from train where numero like :motif");

        if (trains > 0) {
            log.info("{} train(s) de charge supprimé(s), avec leurs horaires et leurs courses.", trains);
        }
        return trains;
    }

    private int executer(String sql) {
        return em.createNativeQuery(sql)
                .setParameter("motif", MOTIF_NUMERO)
                .executeUpdate();
    }

    /**
     * Active lignes the simulator can actually run a train along.
     *
     * <p>The trace check is not defensive padding: {@code courses-du-jour} skips
     * any course whose geometry it cannot build, so a ligne with fewer than two
     * trace points would contribute courses to the database that never move —
     * inflating the count the load profile reports while adding no load at all.
     */
    @SuppressWarnings("unchecked")
    private List<LigneCible> lignesUtilisables() {
        List<Object[]> rangees = em.createNativeQuery(
                        """
                        select id, coalesce(vitesse_max_kmh, 80)
                        from ligne
                        where actif and jsonb_array_length(trace) >= 2
                        order by id
                        """)
                .getResultList();
        return rangees.stream()
                .map(rangee -> new LigneCible(
                        ((Number) rangee[0]).longValue(),
                        ((Number) rangee[1]).shortValue()))
                .toList();
    }

    /**
     * A ligne the profile can run trains on, with the speed those trains may
     * have. {@code vitesse_max_kmh} is nullable on {@code ligne}; 80 is the
     * slowest limit in the seed, so a ligne that declares none gets a train that
     * cannot outrun any real one.
     */
    private record LigneCible(Long id, Short vitesseMaxKmh) {
    }
}
