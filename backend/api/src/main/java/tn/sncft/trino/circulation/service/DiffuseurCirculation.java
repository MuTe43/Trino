package tn.sncft.trino.circulation.service;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import tn.sncft.trino.circulation.domaine.ClasseRetard;
import tn.sncft.trino.circulation.domaine.Course;
import tn.sncft.trino.circulation.domaine.PassageGare;
import tn.sncft.trino.circulation.domaine.StatutCourse;
import tn.sncft.trino.circulation.evenement.EvenementPosition;
import tn.sncft.trino.circulation.evenement.EvenementRetard;
import tn.sncft.trino.circulation.evenement.EvenementStatut;
import tn.sncft.trino.diffusion.HubSse;
import tn.sncft.trino.diffusion.PublicationApresCommit;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns engine outcomes into channel deltas. Sits between the engine and
 * {@link HubSse} so the hub stays a transport and knows nothing about courses,
 * and so the two callers that publish -- ingestion and
 * {@link DetecteurSilence} -- work out their channels the same way.
 *
 * <p>Since phase 8 the same three payloads are also handed to the Spring
 * application context, where {@code MoteurNotification} listens for them after
 * the transaction commits. Deliberately the same records, not a parallel set of
 * notification-shaped events: two event streams describing one movement drift,
 * and the one that drifts is always the one with fewer consumers watching it.
 *
 * <p>The direction matters as much as the reuse. Publishing means circulation
 * has no compile-time knowledge that notifications exist, so the module boundary
 * of decision 1 holds in both directions and the delay engine keeps working
 * unchanged if the notification module is removed.
 */
@Component
public class DiffuseurCirculation {

    private final HubSse hubSse;
    private final ApplicationEventPublisher publicateurEvenements;

    public DiffuseurCirculation(HubSse hubSse, ApplicationEventPublisher publicateurEvenements) {
        this.hubSse = hubSse;
        this.publicateurEvenements = publicateurEvenements;
    }

    public void position(Course course, List<PassageGare> passages, FixPosition fix, OffsetDateTime eta) {
        publier(canaux(course, passages), "position", new EvenementPosition(
                course.getId(),
                fix.latitude(),
                fix.longitude(),
                fix.vitesseKmh(),
                fix.avancementKm(),
                eta));
    }

    public void statut(Course course, List<PassageGare> passages, StatutCourse statut) {
        EvenementStatut evenement = new EvenementStatut(
                course.getId(),
                statut,
                course.getRetardMin(),
                ClasseRetard.de(course.getRetardMin()),
                course.getCauseRetard());
        publier(canaux(course, passages), "statut", evenement);
        publicateurEvenements.publishEvent(evenement);
    }

    /** No-op when nothing moved: an empty delta is not worth a frame. */
    public void retard(Course course, List<PassageGare> passages, List<PassageGare> revises) {
        List<EvenementRetard.PassageRevise> details = revises.stream()
                // A stop revised earlier in the batch and then passed later in
                // the same batch is dropped: its estimate is frozen and the
                // client shows the real time for it, so advertising a revised
                // estimate for it would contradict what the stop now is.
                .filter(passage -> passage.getArriveeReelle() == null)
                .map(passage -> new EvenementRetard.PassageRevise(
                        passage.getGare().getId(),
                        passage.getOrdre(),
                        passage.getArriveeEstimee(),
                        passage.getDepartEstimee(),
                        passage.getRetardMin()))
                .toList();
        if (details.isEmpty()) {
            return;
        }

        EvenementRetard evenement = new EvenementRetard(
                course.getId(),
                course.getRetardMin(),
                ClasseRetard.de(course.getRetardMin()),
                course.getCauseRetard(),
                details);
        publier(canaux(course, passages), "retard", evenement);
        publicateurEvenements.publishEvent(evenement);
    }

    // `position` is deliberately NOT published to the application context. No
    // alert rule reacts to a fix, and a course reports one every few seconds --
    // it would be the highest-volume event in the system, dispatched to a
    // listener that would immediately discard all of it.

    /**
     * Both callers publish from inside a transaction, so the send is deferred to
     * after the commit -- see {@link PublicationApresCommit} for why.
     */
    private void publier(List<String> canaux, String nomEvenement, Object donnees) {
        PublicationApresCommit.publier(hubSse, canaux, nomEvenement, donnees);
    }

    /**
     * The ligne channel, plus the gares this course has not yet cleared.
     *
     * <p>"Not yet cleared" is a chainage test, not an {@code arrivee_reelle}
     * test. The origin has no theoretical arrival, so it never gets a real one
     * either -- keying on that left every course publishing to its origin gare
     * for the whole run, which turned Tunis Ville (the origin of most lignes)
     * into a de facto global channel and broke invariant 5.
     *
     * <p>The comparison is {@code >=} on purpose: a train standing at a stop
     * has not cleared it, and that stop's board is exactly the one that still
     * needs to hear about it.
     */
    private List<String> canaux(Course course, List<PassageGare> passages) {
        BigDecimal avancement = course.getAvancementKm();
        List<String> canaux = new ArrayList<>();
        canaux.add(HubSse.canalLigne(course.getLigne().getId()));
        for (PassageGare passage : passages) {
            if (avancement == null || passage.getPkKm().compareTo(avancement) >= 0) {
                canaux.add(HubSse.canalGare(passage.getGare().getId()));
            }
        }
        return canaux;
    }
}
