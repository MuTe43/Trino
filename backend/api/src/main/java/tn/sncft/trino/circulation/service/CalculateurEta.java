package tn.sncft.trino.circulation.service;

import org.springframework.stereotype.Component;
import tn.sncft.trino.circulation.domaine.Course;
import tn.sncft.trino.circulation.domaine.PassageGare;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * ETA at the next stop: {@code (pk_suivante - avancement) / vitesse_chainage},
 * floored at the theoretical arrival (decision 6). No model, no regression --
 * something that can be explained in a defence.
 *
 * <h2>The unit that would silently corrupt every ETA</h2>
 *
 * {@code avancement_km} and {@code pk_km} are CHAINAGE, measured against the
 * ligne's {@code distance_km}. The trace polyline the simulator interpolates
 * along is a different length -- up to 40% longer on some lignes. The
 * {@code vitesseKmh} carried on a ping is a GROUND speed, because that is what
 * AVL hardware reports.
 *
 * <p>Dividing a chainage delta by a ground speed therefore gives an ETA wrong
 * by a per-ligne factor of up to 1.4. It does not crash and it does not look
 * wrong: 14:44 instead of 14:38 is entirely plausible, and the error would only
 * surface much later as punctuality figures that are quietly a few points off.
 *
 * <p>So this class derives its own speed from chainage and the ping's speed is
 * never read here. The method is called {@link #vitesseChainageKmh} rather than
 * {@code vitesse} precisely so a later session cannot absent-mindedly
 * substitute the ping value.
 */
@Component
public class CalculateurEta {

    /** Fixes spanned by the speed window. */
    static final int K = 6;

    /**
     * Below this the train is standing still or crawling, and dividing by it
     * produces an ETA measured in days. Fall back to the timetable's own pace
     * instead of publishing a number nobody believes.
     */
    private static final double VITESSE_MIN_KMH = 5.0;

    private static final double MS_PAR_HEURE = 3_600_000.0;

    /** ETA at the next stop, or null when the course has no stop ahead of it. */
    public OffsetDateTime pour(Course course, List<PassageGare> passages, EtatCirculation etat) {
        BigDecimal avancement = etat.dernier().avancementKm();
        PassageGare suivante = prochainArret(passages, avancement);
        if (suivante == null || suivante.getArriveeTheorique() == null) {
            return null;
        }

        double restantKm = suivante.getPkKm().subtract(avancement).doubleValue();
        if (restantKm <= 0) {
            return suivante.getArriveeTheorique();
        }

        double vitesse = vitesseChainageKmh(course, passages, etat);
        long secondes = Math.round(restantKm / vitesse * 3600.0);
        OffsetDateTime eta = etat.dernier().horodatage().plusSeconds(secondes);

        // Floored at the theoretical arrival: a train running ahead of the
        // timetable still does not call at a station before it is due.
        return eta.isBefore(suivante.getArriveeTheorique()) ? suivante.getArriveeTheorique() : eta;
    }

    /**
     * Recent speed in CHAINAGE km/h -- {@code (avancement[n] - avancement[n-k])
     * / (horodatage[n] - horodatage[n-k])}. Never the ping's {@code vitesseKmh},
     * which is a ground speed against a longer polyline; see the class comment.
     *
     * <p>Falls back to the theoretical pace of the segment the train is in when
     * there are fewer than two fixes, or when the window shows it stationary.
     */
    double vitesseChainageKmh(Course course, List<PassageGare> passages, EtatCirculation etat) {
        List<FixPosition> fenetre = etat.historique();
        if (fenetre.size() >= 2) {
            FixPosition recent = fenetre.get(fenetre.size() - 1);
            FixPosition ancien = fenetre.get(Math.max(0, fenetre.size() - 1 - K));
            double km = recent.avancementKm().subtract(ancien.avancementKm()).doubleValue();
            double heures = Duration.between(ancien.horodatage(), recent.horodatage()).toMillis() / MS_PAR_HEURE;
            if (km > 0 && heures > 0) {
                double vitesse = km / heures;
                if (vitesse >= VITESSE_MIN_KMH) {
                    return vitesse;
                }
            }
        }
        return allureTheoriqueKmh(course, passages, etat.dernier().avancementKm());
    }

    /**
     * The pace the timetable itself implies for the segment being travelled, in
     * chainage km/h. Falls back to the whole run's pace when the bracketing
     * stops have no usable pair of times.
     */
    private double allureTheoriqueKmh(Course course, List<PassageGare> passages, BigDecimal avancement) {
        PassageGare suivante = prochainArret(passages, avancement);
        PassageGare precedente = arretPrecedent(passages, avancement);

        if (suivante != null && precedente != null
                && suivante.getArriveeTheorique() != null && precedente.getDepartTheorique() != null) {
            double km = suivante.getPkKm().subtract(precedente.getPkKm()).doubleValue();
            double heures = Duration.between(precedente.getDepartTheorique(), suivante.getArriveeTheorique())
                    .toMillis() / MS_PAR_HEURE;
            if (km > 0 && heures > 0) {
                return km / heures;
            }
        }

        double kmTotal = passages.isEmpty()
                ? 0
                : passages.get(passages.size() - 1).getPkKm().doubleValue();
        double heuresTotal = Duration.between(course.getDepartTheorique(), course.getArriveeTheorique())
                .toMillis() / MS_PAR_HEURE;
        if (kmTotal > 0 && heuresTotal > 0) {
            return kmTotal / heuresTotal;
        }
        // A course with no usable timetable at all. Anything is a guess; pick a
        // plausible line speed rather than dividing by zero.
        return 60.0;
    }

    /**
     * The first stop still ahead of a chainage, or null at the terminus.
     * Shared with ingestion and the read services so "which station is next"
     * has one answer. Assumes {@code passages} ascending by ordre, which for a
     * RETOUR course still means ascending pk -- the mirror is baked in at
     * generation.
     */
    static PassageGare prochainArret(List<PassageGare> passages, BigDecimal avancement) {
        for (PassageGare passage : passages) {
            if (passage.getPkKm().compareTo(avancement) > 0) {
                return passage;
            }
        }
        return null;
    }

    /** The last stop at or behind a chainage, or null before the origin. */
    static PassageGare arretPrecedent(List<PassageGare> passages, BigDecimal avancement) {
        PassageGare precedente = null;
        for (PassageGare passage : passages) {
            if (passage.getPkKm().compareTo(avancement) <= 0) {
                precedente = passage;
            }
        }
        return precedente;
    }
}
