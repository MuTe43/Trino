package tn.sncft.trino.simulateur.moteur;

import tn.sncft.trino.simulateur.dto.CourseDuJourDTO;

import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * In-memory state of one simulated run. All of it lives here and nowhere else:
 * the simulator holds no database handle.
 *
 * <p>Restarting this process restarts every already-departed run from km 0,
 * which is what phase-2.md specifies but is worth knowing before phase 3 reads
 * the feed: a course whose slot was hours ago will look enormously late for as
 * long as it takes to catch up. Resuming from {@code course.avancement_km}
 * instead is the obvious improvement if that becomes a problem.
 */
class EtatCourseSimulee {

    private final long courseId;
    private final GeometrieCourse geometrie;
    private final OffsetDateTime departTheorique;
    private final double longueurKm;
    private final double vitesseNominaleKmh;

    private boolean partie;
    private boolean terminee;
    private double avancementKm;
    private double vitesseCouranteKmh;

    private ProfilPerturbation.Type perturbation = ProfilPerturbation.Type.AUCUNE;
    private double perturbationRestanteSec;
    private double facteurRalentissement = 1.0;
    private final boolean seraPerturbee;
    private String cause;

    EtatCourseSimulee(CourseDuJourDTO course, boolean seraPerturbee) {
        this.courseId = course.courseId();
        this.geometrie = GeometrieCourse.depuis(course);
        this.departTheorique = course.departTheorique();
        this.longueurKm = geometrie.longueurTotale();
        this.seraPerturbee = seraPerturbee;
        this.vitesseNominaleKmh = vitesseNominale(course, longueurKm);
    }

    /**
     * The speed the timetable actually implies, capped by what the train and
     * the ligne allow.
     *
     * <p>Running at {@code vitesse_max} instead would put every unperturbed
     * train at its terminus an hour early -- a board full of trains reported
     * eighty minutes ahead of schedule -- and no amount of perturbation would
     * produce a realistic punctuality figure. Delay has to come from the
     * perturbations, so the baseline has to be the plan.
     */
    private static double vitesseNominale(CourseDuJourDTO course, double longueurKm) {
        Duration duree = Duration.between(course.departTheorique(), course.arriveeTheorique());
        double heures = duree.toSeconds() / 3600.0;
        double nominale = heures <= 0 ? 60.0 : longueurKm / heures;

        int plafond = Math.min(
                course.train() != null && course.train().vitesseMaxKmh() != null
                        ? course.train().vitesseMaxKmh() : Integer.MAX_VALUE,
                course.ligne() != null && course.ligne().vitesseMaxKmh() != null
                        ? course.ligne().vitesseMaxKmh() : Integer.MAX_VALUE);

        return Math.min(nominale, plafond);
    }

    long courseId() {
        return courseId;
    }

    boolean estPartie() {
        return partie;
    }

    boolean estTerminee() {
        return terminee;
    }

    boolean doitPartir(OffsetDateTime maintenantSimule) {
        return !partie && !terminee && !maintenantSimule.isBefore(departTheorique);
    }

    void demarrer() {
        this.partie = true;
        this.avancementKm = 0;
        this.vitesseCouranteKmh = vitesseNominaleKmh;
    }

    /**
     * Advances the run by one tick of simulated time.
     *
     * @return true if a perturbation started on this tick
     */
    boolean avancer(double secondesSimulees, ProfilPerturbation profil) {
        if (!partie || terminee) {
            return false;
        }
        boolean nouvellePerturbation = false;

        // How much of this tick the perturbation actually covers. Charging the
        // whole tick would make a 120 s station stop cost 300 s at
        // acceleration 60, where one 5 s tick spans 300 simulated seconds --
        // so the delay a run accumulates would still depend on the
        // acceleration knob, which is the very thing the rate scaling fixed.
        double secondesPerturbees = Math.min(Math.max(perturbationRestanteSec, 0), secondesSimulees);

        if (perturbationRestanteSec > 0) {
            perturbationRestanteSec -= secondesSimulees;
            if (perturbationRestanteSec <= 0) {
                perturbation = ProfilPerturbation.Type.AUCUNE;
                facteurRalentissement = 1.0;
            }
        } else if (seraPerturbee) {
            ProfilPerturbation.Type tirage = profil.tirerType(secondesSimulees);
            if (tirage != ProfilPerturbation.Type.AUCUNE) {
                perturbation = tirage;
                perturbationRestanteSec = profil.dureeSecondes(tirage);
                facteurRalentissement = tirage == ProfilPerturbation.Type.RALENTISSEMENT
                        ? profil.facteurRalentissement() : 0.0;
                if (cause == null) {
                    cause = profil.tirerCause();
                }
                nouvellePerturbation = true;
                secondesPerturbees = Math.min(perturbationRestanteSec, secondesSimulees);
            }
        }

        double vitesseNormale = vitesseNominaleKmh * profil.bruitVitesse();
        double vitessePerturbee = switch (perturbation) {
            case RALENTISSEMENT -> vitesseNormale * facteurRalentissement;
            case ARRET_PROLONGE -> 0;
            case AUCUNE -> vitesseNormale;
        };

        // Split the tick: perturbed for as long as the perturbation lasts,
        // normal for the remainder.
        double secondesNormales = Math.max(0, secondesSimulees - secondesPerturbees);
        this.avancementKm += (vitessePerturbee * secondesPerturbees + vitesseNormale * secondesNormales) / 3600.0;
        this.vitesseCouranteKmh = secondesSimulees <= 0 ? vitesseNormale
                : (vitessePerturbee * secondesPerturbees + vitesseNormale * secondesNormales) / secondesSimulees;

        if (avancementKm >= longueurKm) {
            avancementKm = longueurKm;
            terminee = true;
            vitesseCouranteKmh = 0;
        }
        return nouvellePerturbation;
    }

    ProfilPerturbation.Type perturbation() {
        return perturbation;
    }

    /** True once started and not yet at its terminus. */
    boolean estEnCours() {
        return partie && !terminee;
    }

    double[] position() {
        return geometrie.positionA(avancementKm);
    }

    int vitesseKmh() {
        return (int) Math.round(vitesseCouranteKmh);
    }

    String cause() {
        return cause;
    }
}
