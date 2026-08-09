package tn.sncft.trino.circulation.backfill;

import tn.sncft.trino.circulation.domaine.CauseRetard;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Closed-form restatement of the simulator's perturbation model, for synthesised
 * history.
 *
 * <p>The simulator ({@code simulateur/moteur/ProfilPerturbation}) is kinematic:
 * it perturbs <em>speed</em> tick by tick and lets delay emerge from the
 * geometry, then the API's delay engine measures it. Nothing there exposes a
 * distribution over {@code retard_min} that could be sampled directly, and the
 * api module must not depend on the simulator (invariant 3 -- the feed producer
 * is swappable and nothing else knows it exists). So the parameters are mirrored
 * here and the same model is integrated over a whole journey instead of stepped:
 *
 * <ul>
 *   <li>{@code PART_PERTURBEE} of courses suffer anything at all -- decided once
 *       per course, as {@code seraPerturbee()} does.</li>
 *   <li>Occurrences are drawn Poisson at the simulator's per-hour rates over the
 *       journey duration, which is what rolling {@code tirerType} every tick at
 *       a rate proportional to elapsed simulated time converges to.</li>
 *   <li>A slowdown of {@code T} seconds at speed factor {@code f} covers in
 *       {@code T} what would have taken {@code f·T}, so it loses {@code T(1-f)}.
 *       An overstay loses its full duration.</li>
 * </ul>
 *
 * <p>Expected loss works out at roughly ten minutes per hour of running time on
 * a perturbed course, which reproduces the 23-29 % of courses 5+ minutes late
 * that phase 2 measured against the live simulator. That agreement is the check
 * that this restatement is faithful; if either side's constants move, they have
 * to move together.
 */
final class ModelePerturbation {

    /** Mirrors {@code ProprietesSimulateur.simulateur.part-perturbee}. */
    private static final double PART_PERTURBEE = 0.28;

    /** Mirrors {@code ProfilPerturbation.RALENTISSEMENTS_PAR_HEURE}. */
    private static final double RALENTISSEMENTS_PAR_HEURE = 1.4;

    /** Mirrors {@code ProfilPerturbation.ARRETS_PAR_HEURE}. */
    private static final double ARRETS_PAR_HEURE = 0.7;

    private static final double RALENTISSEMENT_DUREE_MIN_S = 240;
    private static final double RALENTISSEMENT_DUREE_MAX_S = 900;
    private static final double RALENTISSEMENT_FACTEUR_MIN = 0.35;
    private static final double RALENTISSEMENT_FACTEUR_MAX = 0.70;
    private static final double ARRET_DUREE_MIN_S = 120;
    private static final double ARRET_DUREE_MAX_S = 480;

    /** Same weights as {@code ProfilPerturbation.CAUSES}. */
    private record CausePonderee(CauseRetard cause, int poids) {
    }

    private static final List<CausePonderee> CAUSES = List.of(
            new CausePonderee(CauseRetard.SIGNALISATION, 22),
            new CausePonderee(CauseRetard.AFFLUENCE_VOYAGEURS, 20),
            new CausePonderee(CauseRetard.INCIDENT_TECHNIQUE, 18),
            new CausePonderee(CauseRetard.ATTENTE_CORRESPONDANCE, 14),
            new CausePonderee(CauseRetard.TRAVAUX, 12),
            new CausePonderee(CauseRetard.METEO, 8),
            new CausePonderee(CauseRetard.AUTRE, 4),
            new CausePonderee(CauseRetard.ACCIDENT, 2));

    private static final int POIDS_TOTAL = CAUSES.stream().mapToInt(CausePonderee::poids).sum();

    private ModelePerturbation() {
    }

    /**
     * A generator for one course, seeded from its natural key.
     *
     * <p>The key is avalanched first, and that is load-bearing rather than
     * decorative. {@code java.util.Random} scrambles its seed with a single
     * multiply, so near-consecutive seeds produce near-consecutive first
     * outputs -- and the first output here is the {@code < PART_PERTURBEE}
     * gate. Seeding straight from the key, whose values for successive trains
     * on one day differ by a small amount, made that gate come out the same way
     * for every course of a day: whole days were either untroubled or entirely
     * late, and the 28 % share held over nothing. Mixing (murmur3's 64-bit
     * finaliser) decorrelates neighbouring keys.
     */
    static Random aleatoire(long cle) {
        return new Random(melanger(cle));
    }

    private static long melanger(long valeur) {
        long x = valeur;
        x ^= x >>> 33;
        x *= 0xff51afd7ed558ccdL;
        x ^= x >>> 33;
        x *= 0xc4ceb9fe1a85ec53L;
        x ^= x >>> 33;
        return x;
    }

    /** One perturbation: when into the run it starts, and what it costs. */
    record Incident(double instantMin, double perteSecondes) {
    }

    /**
     * The perturbations a course suffers, in no particular order.
     *
     * @param rnd     seeded per course, so a re-run reproduces the same day
     * @param dureeMin the course's planned journey time
     */
    static List<Incident> tirer(Random rnd, double dureeMin) {
        // The draw is made even for an unperturbed course so that adding or
        // removing perturbations never shifts the rest of the sequence for
        // other courses -- the seed is per course, but keeping the number of
        // draws stable makes the model easier to reason about when tuning.
        boolean perturbee = rnd.nextDouble() < PART_PERTURBEE;
        if (!perturbee || dureeMin <= 0) {
            return List.of();
        }

        double heures = dureeMin / 60.0;
        // Drawn once into a local. Left inline in the loop condition, poisson()
        // would be re-evaluated on every iteration -- a fresh count each time,
        // consuming the sequence and ending the loop as soon as one draw came
        // back below the counter.
        int ralentissements = poisson(rnd, RALENTISSEMENTS_PAR_HEURE * heures);
        int arrets = poisson(rnd, ARRETS_PAR_HEURE * heures);

        List<Incident> incidents = new ArrayList<>();
        for (int i = 0; i < ralentissements; i++) {
            double duree = uniforme(rnd, RALENTISSEMENT_DUREE_MIN_S, RALENTISSEMENT_DUREE_MAX_S);
            double facteur = uniforme(rnd, RALENTISSEMENT_FACTEUR_MIN, RALENTISSEMENT_FACTEUR_MAX);
            incidents.add(new Incident(rnd.nextDouble() * dureeMin, duree * (1 - facteur)));
        }
        for (int i = 0; i < arrets; i++) {
            double duree = uniforme(rnd, ARRET_DUREE_MIN_S, ARRET_DUREE_MAX_S);
            incidents.add(new Incident(rnd.nextDouble() * dureeMin, duree));
        }
        return incidents;
    }

    /**
     * Seconds lost by the time the course reaches a stop {@code offsetMin} into
     * the run. Non-decreasing along the journey, which is what makes a
     * backfilled course look like one the delay engine actually tracked.
     */
    static double perteCumulee(List<Incident> incidents, double offsetMin) {
        double total = 0;
        for (Incident incident : incidents) {
            if (incident.instantMin() <= offsetMin) {
                total += incident.perteSecondes();
            }
        }
        return total;
    }

    static CauseRetard tirerCause(Random rnd) {
        int tirage = rnd.nextInt(POIDS_TOTAL);
        int cumul = 0;
        for (CausePonderee candidate : CAUSES) {
            cumul += candidate.poids();
            if (tirage < cumul) {
                return candidate.cause();
            }
        }
        return CauseRetard.AUTRE;
    }

    private static double uniforme(Random rnd, double min, double max) {
        return min + rnd.nextDouble() * (max - min);
    }

    /** Knuth's sampler. Lambda stays under ~10 here, so the loop is short. */
    private static int poisson(Random rnd, double lambda) {
        double limite = Math.exp(-lambda);
        int k = 0;
        double p = 1;
        do {
            k++;
            p *= rnd.nextDouble();
        } while (p > limite);
        return k - 1;
    }
}
