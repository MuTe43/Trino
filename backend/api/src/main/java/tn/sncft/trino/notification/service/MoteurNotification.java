package tn.sncft.trino.notification.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tn.sncft.trino.circulation.domaine.StatutCourse;
import tn.sncft.trino.circulation.dto.CourseResumeDTO;
import tn.sncft.trino.circulation.dto.PassageDTO;
import tn.sncft.trino.circulation.evenement.EvenementRetard;
import tn.sncft.trino.circulation.evenement.EvenementStatut;
import tn.sncft.trino.circulation.service.CourseService;
import tn.sncft.trino.commun.RessourceIntrouvableException;
import tn.sncft.trino.exploitation.domaine.Gravite;
import tn.sncft.trino.exploitation.domaine.StatutIncident;
import tn.sncft.trino.exploitation.evenement.EvenementIncident;
import tn.sncft.trino.iam.dto.UtilisateurDTO;
import tn.sncft.trino.iam.service.UtilisateurService;
import tn.sncft.trino.notification.domaine.Abonnement;
import tn.sncft.trino.notification.domaine.CanalType;
import tn.sncft.trino.notification.domaine.CibleType;
import tn.sncft.trino.notification.domaine.Evenement;
import tn.sncft.trino.notification.domaine.Notification;
import tn.sncft.trino.notification.domaine.RegleAlerte;
import tn.sncft.trino.notification.repo.AbonnementRepository;
import tn.sncft.trino.notification.repo.NotificationRepository;
import tn.sncft.trino.notification.repo.RegleAlerteRepository;
import tn.sncft.trino.referentiel.dto.GareDTO;
import tn.sncft.trino.referentiel.dto.LigneDTO;
import tn.sncft.trino.referentiel.service.GareService;
import tn.sncft.trino.referentiel.service.LigneService;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Turns the deltas the system already publishes into notifications for the
 * people who asked to hear about them.
 *
 * <p>It listens to the very records {@code DiffuseurCirculation} and
 * {@code DiffuseurIncident} hand to the application context -- not to a second
 * event stream invented for this module.
 *
 * <p>Three rules from the phase file, and they matter more than the plumbing:
 *
 * <ul>
 *   <li><b>After commit.</b> {@code AFTER_COMMIT} on every listener. A
 *       notification about a delay that rolled back is worse than no
 *       notification: the passenger cannot un-read it, and nothing later
 *       contradicts it.</li>
 *   <li><b>Deduplicated.</b> {@link Dedoublonneur}, on the course-borne events.
 *       A course that crosses a threshold stays across it, and the engine
 *       re-evaluates on every ping.</li>
 *   <li><b>Never blocking ingestion.</b> {@code @Async} on its own executor, so
 *       none of this -- matching, resolving, or SMTP -- runs on the thread that
 *       ingested the position.</li>
 * </ul>
 *
 * <p>Every foreign read goes through that module's service:
 * {@link CourseService}, {@link LigneService}, {@link GareService},
 * {@link UtilisateurService}. Not one foreign repository is injected here. That
 * is decision 1, and it is written down because this is exactly the class that
 * would break it -- the same pressure that made {@code IncidentService} in phase
 * 6 reach for four of them.
 *
 * <p>No {@code @Transactional} anywhere in this class, deliberately: it runs
 * after a commit on a thread of its own, its reads each open their own read-only
 * transaction inside the service they call, and its writes go through Spring
 * Data, which is transactional in its own right. One long transaction spanning
 * the SMTP handshake is the shape to avoid.
 */
@Component
public class MoteurNotification {

    private static final Logger log = LoggerFactory.getLogger(MoteurNotification.class);

    private final RegleAlerteRepository regleAlerteRepository;
    private final AbonnementRepository abonnementRepository;
    private final NotificationRepository notificationRepository;
    private final Dedoublonneur dedoublonneur;
    private final Dispatcheur dispatcheur;
    private final CourseService courseService;
    private final LigneService ligneService;
    private final GareService gareService;
    private final UtilisateurService utilisateurService;

    public MoteurNotification(RegleAlerteRepository regleAlerteRepository,
                              AbonnementRepository abonnementRepository,
                              NotificationRepository notificationRepository,
                              Dedoublonneur dedoublonneur,
                              Dispatcheur dispatcheur,
                              CourseService courseService,
                              LigneService ligneService,
                              GareService gareService,
                              UtilisateurService utilisateurService) {
        this.regleAlerteRepository = regleAlerteRepository;
        this.abonnementRepository = abonnementRepository;
        this.notificationRepository = notificationRepository;
        this.dedoublonneur = dedoublonneur;
        this.dispatcheur = dispatcheur;
        this.courseService = courseService;
        this.ligneService = ligneService;
        this.gareService = gareService;
        this.utilisateurService = utilisateurService;
    }

    // ------------------------------------------------------------------
    // Listeners
    // ------------------------------------------------------------------

    /**
     * A course crossed, or is still across, a configured delay threshold.
     *
     * <p>{@code fallbackExecution = true} on all three listeners so an event
     * published outside a transaction is still handled. {@code DetecteurSilence}
     * and the ingestion path both publish from inside one today, but a listener
     * that silently does nothing when there is no transaction is a failure mode
     * with no symptom -- the notifications simply never appear.
     */
    @Async(ConfigurationNotification.EXECUTEUR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void surRetard(EvenementRetard evenement) {
        try {
            List<RegleAlerte> regles = reglesActives(Evenement.RETARD_SEUIL).stream()
                    .filter(regle -> regle.getSeuilMin() != null
                            && evenement.retardMin() >= regle.getSeuilMin())
                    .toList();
            if (regles.isEmpty()) {
                return;
            }

            CourseResumeDTO course = courseService.trouverParId(evenement.courseId());
            // The stops whose estimate actually moved: the gares still ahead of
            // this train, which are the ones whose subscribers still care.
            List<Long> gares = evenement.passagesRevises().stream()
                    .map(EvenementRetard.PassageRevise::gareId)
                    .toList();

            String sujet = "Train " + course.numeroTrain() + " — retard de " + evenement.retardMin() + " min";
            String contenu = "Le train " + course.numeroTrain() + " (" + course.ligne().nom()
                    + ") circule avec " + evenement.retardMin() + " minutes de retard"
                    + causeEnClair(evenement.causeRetard()) + ".";

            emettre(Evenement.RETARD_SEUIL, regles, cibles(course, gares),
                    evenement.courseId(), evenement.courseId(), sujet, contenu);
        } catch (RuntimeException e) {
            journaliserEchec("retard", e);
        }
    }

    /** A course was cancelled. Any other transition is not an alertable event. */
    @Async(ConfigurationNotification.EXECUTEUR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void surStatut(EvenementStatut evenement) {
        if (evenement.statut() != StatutCourse.ANNULE) {
            return;
        }
        try {
            List<RegleAlerte> regles = reglesActives(Evenement.COURSE_ANNULEE);
            if (regles.isEmpty()) {
                return;
            }

            CourseResumeDTO course = courseService.trouverParId(evenement.courseId());
            // Every stop, not only those ahead: a cancellation concerns the
            // whole route, including a board that was expecting this train next.
            List<Long> gares = courseService.passages(evenement.courseId()).stream()
                    .map(passage -> passage.gare().id())
                    .toList();

            String sujet = "Train " + course.numeroTrain() + " — course annulée";
            String contenu = "Le train " + course.numeroTrain() + " (" + course.ligne().nom()
                    + ") est annulé" + causeEnClair(evenement.causeRetard()) + ".";

            emettre(Evenement.COURSE_ANNULEE, regles, cibles(course, gares),
                    evenement.courseId(), evenement.courseId(), sujet, contenu);
        } catch (RuntimeException e) {
            journaliserEchec("statut", e);
        }
    }

    /**
     * An incident was declared or resolved.
     *
     * <p>Deduplicated on the <b>incident</b>, not on the course. An incident is
     * not declared once: {@code IncidentService.mettreAJour} republishes the
     * same payload on any edit, and a description correction leaves
     * {@code statut} at {@code OUVERT} -- which reads here as a second
     * declaration. Measured before this was keyed properly: a description-only
     * PATCH took four notifications to eight, and sent the subscriber a second
     * identical email.
     *
     * <p>Keying on the course cannot work for this path, and that is the trap:
     * a ligne-wide incident carries a null course, so every such incident on the
     * network shared one window key -- suppressing genuinely different incidents
     * while still letting an edit of one through.
     *
     * <p>The residual, stated rather than hidden: an edit more than 30 simulated
     * minutes after the declaration re-notifies. That is a far smaller window
     * than the accidental one it replaces, and closing it entirely needs the
     * event to say whether it is a creation -- which would change the SSE
     * payload documented in api-contract.md.
     */
    @Async(ConfigurationNotification.EXECUTEUR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void surIncident(EvenementIncident evenement) {
        Evenement type = typeDIncident(evenement.statut());
        if (type == null) {
            return;
        }
        try {
            List<RegleAlerte> regles = reglesActives(type).stream()
                    .filter(regle -> graviteSuffisante(evenement.gravite(), regle.getGraviteMin()))
                    .toList();
            if (regles.isEmpty()) {
                return;
            }

            String ou = localisationEnClair(evenement);
            String sujet = type == Evenement.INCIDENT_RESOLU
                    ? "Incident résolu" + ou
                    : "Incident " + evenement.gravite().name().toLowerCase(Locale.ROOT) + ou;
            String contenu = type == Evenement.INCIDENT_RESOLU
                    ? "L'incident suivant est résolu : " + evenement.description()
                    : evenement.description();

            emettre(type, regles, ciblesIncident(evenement), evenement.courseId(),
                    evenement.incidentId(), sujet, contenu);
        } catch (RuntimeException e) {
            journaliserEchec("incident", e);
        }
    }

    // ------------------------------------------------------------------
    // Matching and emission
    // ------------------------------------------------------------------

    private List<RegleAlerte> reglesActives(Evenement evenement) {
        return regleAlerteRepository.findByEvenementAndActifTrue(evenement);
    }

    /**
     * Writes one {@link Notification} per (subscriber, channel) and hands each to
     * the dispatcher.
     *
     * <p>The channel set is the intersection of what the rules allow and what the
     * subscriber asked for. Two different questions: an administrator decides
     * that delay alerts may go out by email at all, a passenger decides whether
     * they want one. Either saying no is a no.
     *
     * @param courseId   stored on the row; null for an event with no course
     * @param sujetDedup what the deduplication window is keyed on -- the course
     *                   for a delay, the incident for a declaration. Not the
     *                   same value as {@code courseId}, and conflating them is
     *                   what let an incident notify twice: a ligne-wide incident
     *                   has a null course, so every such incident shared one
     *                   window key and an ordinary edit re-emitted the lot.
     */
    private void emettre(Evenement evenement, List<RegleAlerte> regles, List<Abonnement> abonnements,
                         Long courseId, Long sujetDedup, String sujet, String contenu) {
        if (abonnements.isEmpty()) {
            return;
        }
        Set<CanalType> canauxAutorises = EnumSet.noneOf(CanalType.class);
        regles.forEach(regle -> canauxAutorises.addAll(regle.getCanaux()));

        List<Long> aRemettre = new ArrayList<>();
        for (Abonnement abonnement : abonnements) {
            Set<CanalType> canaux = EnumSet.copyOf(canauxAutorises);
            canaux.retainAll(abonnement.getCanaux());
            if (canaux.isEmpty()) {
                continue;
            }
            // Resolved before the window is consumed: a subscription belonging
            // to a deactivated account must not burn its deduplication slot, or
            // reactivating the account would leave it silent for half an hour.
            UtilisateurDTO compte = compteDe(abonnement);
            if (abonnement.getUtilisateurId() != null && compte == null) {
                continue;
            }
            if (!dedoublonneur.autoriser(abonnement.getId(), evenement, sujetDedup)) {
                continue;
            }

            for (CanalType canal : canaux) {
                String destinataire = destinataire(canal, abonnement, compte);
                if (destinataire == null) {
                    log.warn("Abonnement {} : canal {} sans destinataire, notification ignorée.",
                            abonnement.getId(), canal);
                    continue;
                }
                Notification notification = new Notification();
                notification.setAbonnement(abonnement);
                notification.setEvenement(evenement);
                notification.setCourseId(courseId);
                notification.setDestinataire(destinataire);
                notification.setCanal(canal);
                notification.setSujet(tronquer(sujet, 200));
                notification.setContenu(contenu);
                aRemettre.add(notificationRepository.save(notification).getId());
            }
        }

        // Saved first, dispatched second. Each dispatch opens its own
        // transaction and reloads by id, so a row has to be committed before its
        // task can find it.
        aRemettre.forEach(dispatcheur::remettre);
    }

    // ------------------------------------------------------------------
    // Resolving subscribers
    // ------------------------------------------------------------------

    /** Subscribers of this course, of its ligne, and of the gares concerned. */
    private List<Abonnement> cibles(CourseResumeDTO course, List<Long> gareIds) {
        List<Abonnement> abonnements = new ArrayList<>(
                abonnementRepository.findByCibleTypeAndCibleId(CibleType.COURSE, course.id()));
        abonnements.addAll(
                abonnementRepository.findByCibleTypeAndCibleId(CibleType.LIGNE, course.ligne().id()));
        if (!gareIds.isEmpty()) {
            abonnements.addAll(abonnementRepository.findByCibleTypeAndCibleIdIn(
                    CibleType.GARE, new LinkedHashSet<>(gareIds)));
        }
        return distinctParId(abonnements);
    }

    private List<Abonnement> ciblesIncident(EvenementIncident evenement) {
        List<Abonnement> abonnements = new ArrayList<>();
        if (evenement.courseId() != null) {
            abonnements.addAll(
                    abonnementRepository.findByCibleTypeAndCibleId(CibleType.COURSE, evenement.courseId()));
        }
        if (evenement.ligneId() != null) {
            abonnements.addAll(
                    abonnementRepository.findByCibleTypeAndCibleId(CibleType.LIGNE, evenement.ligneId()));
        }
        if (evenement.gareId() != null) {
            abonnements.addAll(
                    abonnementRepository.findByCibleTypeAndCibleId(CibleType.GARE, evenement.gareId()));
        }
        return distinctParId(abonnements);
    }

    /**
     * One row per subscription however many of its targets the event touched.
     *
     * <p>Somebody following both a train and the ligne it runs on is one person
     * only if the two subscriptions are the same row -- which they are not, so
     * this only de-duplicates a single subscription reached twice (a course
     * whose route visits a gare more than once). Two genuinely different
     * subscriptions still produce two notifications, which is what the
     * subscriber asked for by making both.
     */
    private List<Abonnement> distinctParId(List<Abonnement> abonnements) {
        Map<Long, Abonnement> parId = new LinkedHashMap<>();
        for (Abonnement abonnement : abonnements) {
            parId.putIfAbsent(abonnement.getId(), abonnement);
        }
        return new ArrayList<>(parId.values());
    }

    /** The account behind an account subscription, or null (including when deactivated). */
    private UtilisateurDTO compteDe(Abonnement abonnement) {
        if (abonnement.getUtilisateurId() == null) {
            return null;
        }
        return utilisateurService.trouverActifParId(abonnement.getUtilisateurId()).orElse(null);
    }

    /**
     * Where this channel delivers.
     *
     * <p>Only {@code EMAIL} carries a real address. The others are addressed by
     * subscription reference on purpose: for {@code IN_APP} the address would be
     * the bearer token, which does not belong in a table anyone lists, and the
     * frame reaches the right connection through the channel name anyway. The
     * model holds no phone number at all, which is the honest reason
     * {@link tn.sncft.trino.notification.canal.CanalSmsStub} could not send even
     * if it had an account.
     */
    private String destinataire(CanalType canal, Abonnement abonnement, UtilisateurDTO compte) {
        if (canal != CanalType.EMAIL) {
            return "abonnement:" + abonnement.getId();
        }
        if (abonnement.getEmail() != null && !abonnement.getEmail().isBlank()) {
            return abonnement.getEmail();
        }
        return compte == null ? null : compte.email();
    }

    // ------------------------------------------------------------------
    // Wording
    // ------------------------------------------------------------------

    private static Evenement typeDIncident(StatutIncident statut) {
        if (statut == StatutIncident.OUVERT) {
            return Evenement.INCIDENT_DECLARE;
        }
        if (statut == StatutIncident.RESOLU) {
            return Evenement.INCIDENT_RESOLU;
        }
        // EN_COURS: a working note between two states a subscriber was already
        // told about, not an event of its own.
        return null;
    }

    /** Null {@code graviteMin} means every severity, which is what the seeded rules use. */
    private static boolean graviteSuffisante(Gravite gravite, Gravite minimum) {
        return minimum == null || gravite.ordinal() >= minimum.ordinal();
    }

    /**
     * " sur la ligne Tunis - Gabès" / " en gare de Sousse", or nothing.
     *
     * <p>Resolved to names through the référentiel services rather than left as
     * ids: this string goes into an email subject a passenger reads, and
     * "Incident majeure sur la ligne 1" is a sentence written for the database.
     * A lookup that fails degrades to no location rather than losing the
     * notification.
     */
    private String localisationEnClair(EvenementIncident evenement) {
        if (evenement.gareId() != null) {
            return nom(() -> gareService.trouverParId(evenement.gareId()), GareDTO::nom)
                    .map(nom -> " en gare de " + nom).orElse("");
        }
        if (evenement.ligneId() != null) {
            return nom(() -> ligneService.trouverParId(evenement.ligneId()), LigneDTO::nom)
                    .map(nom -> " sur la ligne " + nom).orElse("");
        }
        return "";
    }

    private <T> Optional<String> nom(Supplier<T> lecture, Function<T, String> extraction) {
        try {
            // ofNullable around the lookup as well as the extraction: a
            // référentiel row that has gone missing must cost this notification
            // its location, not the notification itself. The broad catch further
            // up would otherwise turn a null here into "no notification at all",
            // logged at WARN and looking like a dead engine.
            return Optional.ofNullable(lecture.get()).map(extraction);
        } catch (RessourceIntrouvableException e) {
            return Optional.empty();
        }
    }

    /** " (cause : signalisation)", or nothing when no cause has been attributed. */
    private static String causeEnClair(Enum<?> cause) {
        return cause == null ? "" : " (cause : " + cause.name().toLowerCase(Locale.ROOT).replace('_', ' ') + ")";
    }

    private static String tronquer(String valeur, int longueur) {
        return valeur.length() <= longueur ? valeur : valeur.substring(0, longueur);
    }

    /**
     * An async listener whose exception escapes is swallowed by the executor,
     * so the failure would be a notification that never arrives and never
     * explains itself. Logged at WARN and dropped: one bad event must not stop
     * the next one from being handled.
     */
    private void journaliserEchec(String flux, RuntimeException e) {
        log.warn("Notification non émise sur l'événement {} : {}", flux, e.toString());
    }
}
