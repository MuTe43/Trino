package tn.sncft.trino.simulateur.moteur;

import org.springframework.stereotype.Component;
import tn.sncft.trino.simulateur.config.ProprietesSimulateur;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Injects the trouble that makes a tracking dashboard worth looking at.
 *
 * <p>Two shapes, because they degrade a journey differently and phase 3 has to
 * cope with both: a stretch of reduced speed between stops, and an overstay at
 * a station. Roughly {@code part-perturbee} of courses are picked to suffer
 * one -- enough that the board is not uniformly green, not so much that
 * punctuality looks broken.
 *
 * <p>The cause is drawn from a weighted table rather than uniformly: signalling
 * faults and passenger crowding are everyday events, accidents are not, and a
 * report where accidents account for an eighth of all delays would be nonsense.
 */
@Component
public class ProfilPerturbation {

    /** What a perturbation is doing to a course right now. */
    public enum Type {
        AUCUNE,
        RALENTISSEMENT,
        ARRET_PROLONGE
    }

    private record CausePonderee(String cause, int poids) {
    }

    private static final List<CausePonderee> CAUSES = List.of(
            new CausePonderee("SIGNALISATION", 22),
            new CausePonderee("AFFLUENCE_VOYAGEURS", 20),
            new CausePonderee("INCIDENT_TECHNIQUE", 18),
            new CausePonderee("ATTENTE_CORRESPONDANCE", 14),
            new CausePonderee("TRAVAUX", 12),
            new CausePonderee("METEO", 8),
            new CausePonderee("AUTRE", 4),
            new CausePonderee("ACCIDENT", 2));

    private static final int POIDS_TOTAL = CAUSES.stream().mapToInt(CausePonderee::poids).sum();

    private final double partPerturbee;

    public ProfilPerturbation(ProprietesSimulateur proprietes) {
        this.partPerturbee = proprietes.getSimulateur().getPartPerturbee();
    }

    /** Decided once when a course starts, so a run's fate is stable. */
    public boolean seraPerturbee() {
        return ThreadLocalRandom.current().nextDouble() < partPerturbee;
    }

    public String tirerCause() {
        int tirage = ThreadLocalRandom.current().nextInt(POIDS_TOTAL);
        int cumul = 0;
        for (CausePonderee candidate : CAUSES) {
            cumul += candidate.poids();
            if (tirage < cumul) {
                return candidate.cause();
            }
        }
        return "AUTRE";
    }

    /** Expected perturbations per hour of simulated running time. */
    private static final double RALENTISSEMENTS_PAR_HEURE = 1.4;
    private static final double ARRETS_PAR_HEURE = 0.7;

    /**
     * Rolls for a new perturbation, at a rate proportional to the simulated
     * time that just elapsed.
     *
     * <p>Scaling by simulated time and not by tick is the whole point. A fixed
     * per-tick probability makes punctuality a function of
     * {@code acceleration}: the same journey gets ~3960 rolls at x1 and ~66 at
     * x60, so the demo setting the phase spec recommends would report a
     * completely different delay rate from a real-time run.
     */
    public Type tirerType(double secondesSimulees) {
        double heures = Math.max(0, secondesSimulees) / 3600.0;
        // Capped: past ~1714 simulated seconds per tick the raw rates sum
        // above 1 and every tick would draw a perturbation, which at high
        // acceleration would silently turn the rate back into a per-tick one.
        double pRalentissement = Math.min(RALENTISSEMENTS_PAR_HEURE * heures, 0.5);
        double pArret = Math.min(ARRETS_PAR_HEURE * heures, 0.25);

        double tirage = ThreadLocalRandom.current().nextDouble();
        if (tirage < pRalentissement) {
            return Type.RALENTISSEMENT;
        }
        if (tirage < pRalentissement + pArret) {
            return Type.ARRET_PROLONGE;
        }
        return Type.AUCUNE;
    }

    /** Speed multiplier while slowed down. */
    public double facteurRalentissement() {
        return ThreadLocalRandom.current().nextDouble(0.35, 0.70);
    }

    /** How long a perturbation lasts, in simulated seconds. */
    public double dureeSecondes(Type type) {
        return switch (type) {
            case RALENTISSEMENT -> ThreadLocalRandom.current().nextDouble(240, 900);
            case ARRET_PROLONGE -> ThreadLocalRandom.current().nextDouble(120, 480);
            case AUCUNE -> 0;
        };
    }

    /**
     * Per-tick speed jitter applied to every course, perturbed or not.
     *
     * <p>Centred on 1.0, and that matters: the range 0.88-1.04 looks like
     * harmless noise but has mean 0.96, which is a 4% speed deficit applied on
     * every tick of every run. That alone put L1 fourteen minutes late with no
     * perturbation at all, so a quarter of the fleet was structurally late
     * before ProfilPerturbation did anything. Delay must come from the
     * perturbations; the baseline is the plan.
     */
    public double bruitVitesse() {
        return ThreadLocalRandom.current().nextDouble(0.94, 1.06);
    }
}
