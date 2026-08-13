package tn.sncft.trino.notification.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tn.sncft.trino.circulation.domaine.ClasseRetard;
import tn.sncft.trino.circulation.domaine.SensCourse;
import tn.sncft.trino.circulation.domaine.StatutCourse;
import tn.sncft.trino.circulation.dto.CourseResumeDTO;
import tn.sncft.trino.circulation.evenement.EvenementRetard;
import tn.sncft.trino.circulation.evenement.EvenementStatut;
import tn.sncft.trino.circulation.service.CourseService;
import tn.sncft.trino.exploitation.domaine.Gravite;
import tn.sncft.trino.exploitation.domaine.StatutIncident;
import tn.sncft.trino.exploitation.domaine.TypeIncident;
import tn.sncft.trino.exploitation.evenement.EvenementIncident;
import tn.sncft.trino.iam.domaine.Role;
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
import tn.sncft.trino.referentiel.domaine.TypeTrain;
import tn.sncft.trino.referentiel.dto.LigneDTO;
import tn.sncft.trino.referentiel.service.GareService;
import tn.sncft.trino.referentiel.service.LigneService;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Matching: which rules fire, whom they reach, and on which channels.
 *
 * <p>Everything is mocked, so nothing here touches a database or an executor.
 * The engine's own asynchrony is a Spring concern; what it decides is not, and
 * that is what these pin.
 */
class MoteurNotificationTest {

    private RegleAlerteRepository regleAlerteRepository;
    private AbonnementRepository abonnementRepository;
    private NotificationRepository notificationRepository;
    private Dedoublonneur dedoublonneur;
    private Dispatcheur dispatcheur;
    private CourseService courseService;
    private LigneService ligneService;
    private UtilisateurService utilisateurService;
    private MoteurNotification moteur;

    private final List<Notification> enregistrees = new ArrayList<>();

    @BeforeEach
    void preparer() {
        regleAlerteRepository = mock(RegleAlerteRepository.class);
        abonnementRepository = mock(AbonnementRepository.class);
        notificationRepository = mock(NotificationRepository.class);
        dedoublonneur = mock(Dedoublonneur.class);
        dispatcheur = mock(Dispatcheur.class);
        courseService = mock(CourseService.class);
        ligneService = mock(LigneService.class);
        utilisateurService = mock(UtilisateurService.class);

        moteur = new MoteurNotification(regleAlerteRepository, abonnementRepository,
                notificationRepository, dedoublonneur, dispatcheur, courseService,
                ligneService, mock(GareService.class), utilisateurService);

        when(ligneService.trouverParId(anyLong())).thenReturn(new LigneDTO(
                1L, "L1", "Tunis - Gabès", null, null, null, List.of(), true));

        AtomicLong sequence = new AtomicLong();
        when(notificationRepository.save(any())).thenAnswer(invocation -> {
            Notification notification = invocation.getArgument(0);
            notification.setId(sequence.incrementAndGet());
            enregistrees.add(notification);
            return notification;
        });
        when(dedoublonneur.autoriser(anyLong(), any(), any())).thenReturn(true);
        when(courseService.trouverParId(anyLong())).thenReturn(course());
        when(courseService.passages(anyLong())).thenReturn(List.of());
        when(abonnementRepository.findByCibleTypeAndCibleId(any(), anyLong())).thenReturn(List.of());
        when(abonnementRepository.findByCibleTypeAndCibleIdIn(any(), any())).thenReturn(List.of());
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("sous le seuil, rien n'est émis")
    void sousLeSeuilRienNEstEmis() {
        regles(regle(Evenement.RETARD_SEUIL, (short) 15, null, EnumSet.allOf(CanalType.class)));
        abonnesCourse(anonyme(1L, EnumSet.of(CanalType.IN_APP)));

        moteur.surRetard(retard(9));

        assertThat(enregistrees).isEmpty();
        verify(dispatcheur, never()).remettre(anyLong());
    }

    @Test
    @DisplayName("au-dessus du seuil, une notification par canal retenu")
    void auDessusDuSeuilUneNotificationParCanal() {
        regles(regle(Evenement.RETARD_SEUIL, (short) 5, null, EnumSet.allOf(CanalType.class)));
        abonnesCourse(anonyme(1L, EnumSet.of(CanalType.IN_APP, CanalType.EMAIL)));

        moteur.surRetard(retard(12));

        assertThat(enregistrees).hasSize(2);
        assertThat(enregistrees).extracting(Notification::getCanal)
                .containsExactlyInAnyOrder(CanalType.IN_APP, CanalType.EMAIL);
        assertThat(enregistrees).allSatisfy(notification -> {
            assertThat(notification.getEvenement()).isEqualTo(Evenement.RETARD_SEUIL);
            assertThat(notification.getCourseId()).isEqualTo(42L);
            assertThat(notification.getSujet()).contains("DR201");
        });
        verify(dispatcheur, org.mockito.Mockito.times(2)).remettre(anyLong());
    }

    /**
     * The rule says what an event may use, the subscription says what its owner
     * wants, and the engine emits on the intersection. Either saying no is a no --
     * an administrator unticking EMAIL silences it for everybody, which is the
     * point of having the screen.
     */
    @Test
    @DisplayName("les canaux émis sont l'intersection de la règle et de l'abonnement")
    void canauxSontLIntersection() {
        regles(regle(Evenement.RETARD_SEUIL, (short) 5, null, EnumSet.of(CanalType.IN_APP)));
        abonnesCourse(anonyme(1L, EnumSet.of(CanalType.IN_APP, CanalType.EMAIL)));

        moteur.surRetard(retard(12));

        assertThat(enregistrees).extracting(Notification::getCanal).containsExactly(CanalType.IN_APP);
    }

    @Test
    @DisplayName("une intersection vide n'émet rien")
    void intersectionVideNEmetRien() {
        regles(regle(Evenement.RETARD_SEUIL, (short) 5, null, EnumSet.of(CanalType.SMS)));
        abonnesCourse(anonyme(1L, EnumSet.of(CanalType.IN_APP)));

        moteur.surRetard(retard(12));

        assertThat(enregistrees).isEmpty();
    }

    /**
     * A subscription outlives the account behind it -- a user row is never
     * deleted (decision 11), so deactivating someone leaves their subscriptions
     * in place. Without this check they would keep receiving mail for ever.
     */
    @Test
    @DisplayName("un compte désactivé ne reçoit rien")
    void compteDesactiveNeRecoitRien() {
        regles(regle(Evenement.RETARD_SEUIL, (short) 5, null, EnumSet.allOf(CanalType.class)));
        abonnesCourse(deCompte(1L, 7L, EnumSet.of(CanalType.IN_APP)));
        when(utilisateurService.trouverActifParId(7L)).thenReturn(Optional.empty());

        moteur.surRetard(retard(12));

        assertThat(enregistrees).isEmpty();
        // And the deduplication slot is untouched, so reactivating the account
        // does not leave it silent for the rest of the window.
        verify(dedoublonneur, never()).autoriser(anyLong(), any(), any());
    }

    @Test
    @DisplayName("un compte actif reçoit, à l'adresse du compte quand l'abonnement n'en porte pas")
    void compteActifRecoitALAdresseDuCompte() {
        regles(regle(Evenement.RETARD_SEUIL, (short) 5, null, EnumSet.of(CanalType.EMAIL)));
        abonnesCourse(deCompte(1L, 7L, EnumSet.of(CanalType.EMAIL)));
        when(utilisateurService.trouverActifParId(7L)).thenReturn(Optional.of(
                new UtilisateurDTO(7L, "voyageur@sncft.tn", "Voyageur", Role.VOYAGEUR, true)));

        moteur.surRetard(retard(12));

        assertThat(enregistrees).hasSize(1);
        assertThat(enregistrees.get(0).getDestinataire()).isEqualTo("voyageur@sncft.tn");
    }

    @Test
    @DisplayName("le dédoublonneur coupe l'émission")
    void dedoublonneurCoupeLEmission() {
        regles(regle(Evenement.RETARD_SEUIL, (short) 5, null, EnumSet.allOf(CanalType.class)));
        abonnesCourse(anonyme(1L, EnumSet.of(CanalType.IN_APP)));
        when(dedoublonneur.autoriser(anyLong(), any(), any())).thenReturn(false);

        moteur.surRetard(retard(12));

        assertThat(enregistrees).isEmpty();
    }

    @Test
    @DisplayName("seule l'annulation déclenche COURSE_ANNULEE")
    void seuleLAnnulationDeclenche() {
        regles(regle(Evenement.COURSE_ANNULEE, null, null, EnumSet.allOf(CanalType.class)));
        abonnesCourse(anonyme(1L, EnumSet.of(CanalType.IN_APP)));

        moteur.surStatut(new EvenementStatut(42L, StatutCourse.RETARDE, 12,
                ClasseRetard.R10, null));
        assertThat(enregistrees).isEmpty();

        moteur.surStatut(new EvenementStatut(42L, StatutCourse.ANNULE, 0,
                ClasseRetard.A_L_HEURE, null));
        assertThat(enregistrees).hasSize(1);
        assertThat(enregistrees.get(0).getEvenement()).isEqualTo(Evenement.COURSE_ANNULEE);
    }

    @Test
    @DisplayName("un incident sous la gravité minimale est ignoré")
    void incidentSousLaGraviteMinimaleEstIgnore() {
        regles(regle(Evenement.INCIDENT_DECLARE, null, Gravite.MAJEURE, EnumSet.allOf(CanalType.class)));
        when(abonnementRepository.findByCibleTypeAndCibleId(CibleType.LIGNE, 1L))
                .thenReturn(List.of(anonyme(1L, EnumSet.of(CanalType.IN_APP))));

        moteur.surIncident(incident(Gravite.MOYENNE, StatutIncident.OUVERT));
        assertThat(enregistrees).isEmpty();

        moteur.surIncident(incident(Gravite.CRITIQUE, StatutIncident.OUVERT));
        assertThat(enregistrees).hasSize(1);
    }

    /**
     * {@code EN_COURS} is a working note between two states the subscriber was
     * already told about, not an event of its own.
     */
    @Test
    @DisplayName("un incident passé EN_COURS n'est pas un événement notifiable")
    void incidentEnCoursNEstPasNotifiable() {
        regles(regle(Evenement.INCIDENT_DECLARE, null, null, EnumSet.allOf(CanalType.class)));
        when(abonnementRepository.findByCibleTypeAndCibleId(CibleType.LIGNE, 1L))
                .thenReturn(List.of(anonyme(1L, EnumSet.of(CanalType.IN_APP))));

        moteur.surIncident(incident(Gravite.CRITIQUE, StatutIncident.EN_COURS));

        assertThat(enregistrees).isEmpty();
    }

    /**
     * Regression. {@code IncidentService.mettreAJour} republishes the same
     * payload on any edit, and a description correction leaves {@code statut} at
     * {@code OUVERT} — which reads here as a second declaration. Measured
     * against a running API before this was keyed properly: a description-only
     * PATCH took four notifications to eight and sent a second identical email.
     *
     * <p>The key is the **incident**, not the course. Keying on the course
     * cannot work: a ligne-wide incident carries a null course, so every such
     * incident on the network would share one window.
     */
    @Test
    @DisplayName("republier un incident OUVERT ne notifie pas une seconde fois")
    void republierUnIncidentNeNotifiePasDeuxFois() {
        regles(regle(Evenement.INCIDENT_DECLARE, null, null, EnumSet.allOf(CanalType.class)));
        when(abonnementRepository.findByCibleTypeAndCibleId(CibleType.LIGNE, 1L))
                .thenReturn(List.of(anonyme(1L, EnumSet.of(CanalType.IN_APP))));

        moteur.surIncident(incident(Gravite.MAJEURE, StatutIncident.OUVERT));
        assertThat(enregistrees).hasSize(1);

        // The window is consumed, so the republication is suppressed.
        when(dedoublonneur.autoriser(1L, Evenement.INCIDENT_DECLARE, 5L)).thenReturn(false);
        moteur.surIncident(incident(Gravite.MAJEURE, StatutIncident.OUVERT));

        assertThat(enregistrees).hasSize(1);
        verify(dedoublonneur, times(2)).autoriser(1L, Evenement.INCIDENT_DECLARE, 5L);
    }

    /**
     * Two different incidents must both get through, whatever their courses. It
     * is the incident id that separates them — the whole point of not keying
     * this path on the course.
     */
    @Test
    @DisplayName("deux incidents distincts sur la même ligne notifient tous les deux")
    void deuxIncidentsDistinctsNotifientTousLesDeux() {
        regles(regle(Evenement.INCIDENT_DECLARE, null, null, EnumSet.allOf(CanalType.class)));
        when(abonnementRepository.findByCibleTypeAndCibleId(CibleType.LIGNE, 1L))
                .thenReturn(List.of(anonyme(1L, EnumSet.of(CanalType.IN_APP))));

        moteur.surIncident(incident(5L, Gravite.MAJEURE, StatutIncident.OUVERT));
        moteur.surIncident(incident(6L, Gravite.MAJEURE, StatutIncident.OUVERT));

        assertThat(enregistrees).hasSize(2);
        verify(dedoublonneur).autoriser(1L, Evenement.INCIDENT_DECLARE, 5L);
        verify(dedoublonneur).autoriser(1L, Evenement.INCIDENT_DECLARE, 6L);
    }

    /**
     * A course whose route calls at the same gare twice, or a subscriber matched
     * by more than one lookup, is still one subscription and must be told once.
     */
    @Test
    @DisplayName("un abonnement atteint deux fois n'est notifié qu'une fois")
    void abonnementAtteintDeuxFoisNotifieUneFois() {
        regles(regle(Evenement.RETARD_SEUIL, (short) 5, null, EnumSet.of(CanalType.IN_APP)));
        Abonnement abonnement = anonyme(1L, EnumSet.of(CanalType.IN_APP));
        when(abonnementRepository.findByCibleTypeAndCibleId(CibleType.COURSE, 42L))
                .thenReturn(List.of(abonnement));
        when(abonnementRepository.findByCibleTypeAndCibleIdIn(any(), any()))
                .thenReturn(List.of(abonnement));

        moteur.surRetard(retard(12));

        assertThat(enregistrees).hasSize(1);
    }

    // ------------------------------------------------------------------

    private void regles(RegleAlerte... regles) {
        for (Evenement evenement : Evenement.values()) {
            when(regleAlerteRepository.findByEvenementAndActifTrue(evenement)).thenReturn(List.of());
        }
        for (RegleAlerte regle : regles) {
            when(regleAlerteRepository.findByEvenementAndActifTrue(regle.getEvenement()))
                    .thenReturn(List.of(regle));
        }
    }

    private void abonnesCourse(Abonnement... abonnements) {
        when(abonnementRepository.findByCibleTypeAndCibleId(CibleType.COURSE, 42L))
                .thenReturn(List.of(abonnements));
    }

    private static RegleAlerte regle(Evenement evenement, Short seuil, Gravite gravite, Set<CanalType> canaux) {
        RegleAlerte regle = new RegleAlerte();
        regle.setId(1L);
        regle.setEvenement(evenement);
        regle.setSeuilMin(seuil);
        regle.setGraviteMin(gravite);
        regle.setCanaux(canaux);
        regle.setActif(true);
        return regle;
    }

    private static Abonnement anonyme(Long id, Set<CanalType> canaux) {
        Abonnement abonnement = new Abonnement();
        abonnement.setId(id);
        abonnement.setJetonAnonyme("JETON" + id);
        abonnement.setCibleType(CibleType.COURSE);
        abonnement.setCibleId(42L);
        abonnement.setCanaux(canaux);
        abonnement.setEmail("voyageur@exemple.tn");
        return abonnement;
    }

    private static Abonnement deCompte(Long id, Long utilisateurId, Set<CanalType> canaux) {
        Abonnement abonnement = anonyme(id, canaux);
        abonnement.setJetonAnonyme(null);
        abonnement.setUtilisateurId(utilisateurId);
        abonnement.setEmail(null);
        return abonnement;
    }

    private static EvenementRetard retard(int retardMin) {
        return new EvenementRetard(42L, retardMin, ClasseRetard.de(retardMin), null, List.of());
    }

    private static EvenementIncident incident(Gravite gravite, StatutIncident statut) {
        return incident(5L, gravite, statut);
    }

    private static EvenementIncident incident(Long incidentId, Gravite gravite, StatutIncident statut) {
        return new EvenementIncident(incidentId, TypeIncident.OBSTACLE_VOIE, gravite, statut,
                "Obstacle sur la voie", OffsetDateTime.parse("2026-08-12T06:00:00Z"),
                1L, null, null, null, null);
    }

    private static CourseResumeDTO course() {
        return new CourseResumeDTO(42L, "DR201", "Le Sahel", TypeTrain.GRANDES_LIGNES,
                new CourseResumeDTO.LigneBreveDTO(1L, "Tunis - Gabès"), SensCourse.ALLER,
                StatutCourse.RETARDE, 12, ClasseRetard.R10, null,
                OffsetDateTime.parse("2026-08-12T06:00:00Z"),
                OffsetDateTime.parse("2026-08-12T12:00:00Z"),
                null, null, null, null);
    }
}
