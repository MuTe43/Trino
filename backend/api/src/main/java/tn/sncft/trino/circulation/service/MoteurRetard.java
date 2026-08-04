package tn.sncft.trino.circulation.service;

import org.springframework.stereotype.Component;
import tn.sncft.trino.circulation.domaine.Course;
import tn.sncft.trino.circulation.domaine.PassageGare;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Stamps real times, measures delay and pushes it forward through the rest of
 * the run. It does not touch {@code course.statut} -- that belongs to
 * {@link MachineEtatCourse} and to nothing else.
 *
 * <h2>Margin absorption</h2>
 *
 * Carrying the same delay unchanged all the way to the terminus is wrong, and
 * an operations engineer will say so. Real timetables are padded, and
 * {@code marge_min} is that padding: the slack built into the segment arriving
 * at each stop. A train 11 minutes late at Sousse should reach Sfax perhaps 6
 * minutes late, not 11.
 *
 * <p>The margin is read from {@code passage_gare}, never joined back from
 * {@code desserte}. A RETOUR course walks the desserte mirrored, so the
 * desserte row would give the wrong stop's padding, and this is the hot path.
 */
@Component
public class MoteurRetard {

    /**
     * Applies one position fix.
     *
     * @param avancementKm chainage of the fix, already projected
     * @param horodatage   the fix's own timestamp -- the feed's clock, not the
     *                     server's, so real times stay meaningful under an
     *                     accelerated simulation
     */
    public ResultatRetard traiter(Course course, List<PassageGare> passages,
                                  OffsetDateTime horodatage, BigDecimal avancementKm) {
        List<PassageGare> ordonnes = trier(passages);
        boolean franchissement = false;
        PassageGare dernierAtteint = null;

        for (PassageGare passage : ordonnes) {
            int position = passage.getPkKm().compareTo(avancementKm);
            boolean atteint = position <= 0;
            boolean depasse = position < 0;

            // Every unstamped stop behind the train is stamped, not just the
            // last one. Under a normal ping rate that is the same stop; after a
            // gap in the feed it is what stops an intermediate station from
            // keeping a null real time for the rest of the day, which would
            // also keep the run from ever reaching TERMINUS_ATTEINT.
            if (atteint && passage.getArriveeTheorique() != null && passage.getArriveeReelle() == null) {
                passage.setArriveeReelle(horodatage);
                passage.setRetardMin(minutesEntre(passage.getArriveeTheorique(), horodatage));
                franchissement = true;
            }
            if (depasse && passage.getDepartTheorique() != null && passage.getDepartReelle() == null) {
                passage.setDepartReelle(horodatage);
                if (passage.getArriveeTheorique() == null) {
                    // The origin has no arrival to measure against, so its
                    // departure is what carries the delay of the run's start.
                    passage.setRetardMin(minutesEntre(passage.getDepartTheorique(), horodatage));
                }
                franchissement = true;
            }
            if (atteint) {
                dernierAtteint = passage;
            }
        }

        int retard = dernierAtteint != null ? dernierAtteint.getRetardMin() : course.getRetardMin();
        course.setRetardMin(retard);

        short apres = dernierAtteint != null ? dernierAtteint.getOrdre() : 0;
        return new ResultatRetard(retard, franchissement, propager(ordonnes, retard, apres));
    }

    /**
     * Walks the stops after {@code apresOrdre} carrying the delay forward,
     * letting each segment's margin absorb what it can, and returns the stops
     * whose estimate actually moved -- the SSE delta, never the full list.
     *
     * <p>A stop that already has a real arrival is left alone: once observed,
     * its estimate is frozen and the UI shows the real time instead.
     */
    public List<PassageGare> propager(List<PassageGare> passages, int retardCourant, short apresOrdre) {
        List<PassageGare> revises = new ArrayList<>();
        int retard = retardCourant;

        for (PassageGare passage : trier(passages)) {
            if (passage.getOrdre() < apresOrdre) {
                // Behind the train and fully observed.
                continue;
            }

            if (passage.getOrdre() == apresOrdre) {
                // The stop the train is standing at. Its arrival is observed,
                // so retard_min and arrivee_estimee are frozen -- but until it
                // actually pulls out, its DEPARTURE estimate has to keep
                // moving. Freezing that too let a train held at the platform
                // slip past its own stale departEstimee and disappear from
                // /gares/{id}/departs, which filters on it. The freeze rule in
                // the domain model is about arrivee_estimee, not both.
                if (passage.getDepartTheorique() != null && passage.getDepartReelle() == null
                        && majDepartEstimee(passage, retard)) {
                    revises.add(passage);
                }
                continue;
            }

            // Genuinely ahead: the margin on this segment can still absorb.
            retard = Math.max(0, retard - passage.getMargeMin());

            OffsetDateTime arriveeAvant = passage.getArriveeEstimee();
            boolean bouge = false;

            passage.setRetardMin(retard);
            if (passage.getArriveeTheorique() != null && passage.getArriveeReelle() == null) {
                passage.setArriveeEstimee(passage.getArriveeTheorique().plusMinutes(retard));
                bouge = !Objects.equals(arriveeAvant, passage.getArriveeEstimee());
            }
            if (passage.getDepartTheorique() != null && passage.getDepartReelle() == null) {
                bouge |= majDepartEstimee(passage, retard);
            }

            if (bouge) {
                revises.add(passage);
            }
        }
        return revises;
    }

    /** @return whether the departure estimate actually moved */
    private boolean majDepartEstimee(PassageGare passage, int retard) {
        OffsetDateTime avant = passage.getDepartEstimee();
        passage.setDepartEstimee(passage.getDepartTheorique().plusMinutes(retard));
        return !Objects.equals(avant, passage.getDepartEstimee());
    }

    /** Rounded to the minute, and signed: a train can arrive early. */
    public static int minutesEntre(OffsetDateTime theorique, OffsetDateTime reel) {
        return (int) Math.round(Duration.between(theorique, reel).toSeconds() / 60.0);
    }

    private List<PassageGare> trier(List<PassageGare> passages) {
        return passages.stream().sorted(Comparator.comparing(PassageGare::getOrdre)).toList();
    }

    /**
     * @param franchissement whether this fix stamped a real time, i.e. whether
     *                       the run actually passed something
     * @param revises        stops whose estimate moved, for the SSE delta
     */
    public record ResultatRetard(int retardMin, boolean franchissement, List<PassageGare> revises) {
    }
}
