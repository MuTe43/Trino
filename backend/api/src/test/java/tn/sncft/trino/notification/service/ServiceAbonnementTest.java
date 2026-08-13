package tn.sncft.trino.notification.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tn.sncft.trino.commun.RessourceIntrouvableException;
import tn.sncft.trino.notification.domaine.Abonnement;
import tn.sncft.trino.notification.domaine.CanalType;
import tn.sncft.trino.notification.domaine.CibleType;
import tn.sncft.trino.notification.dto.AbonnementCreateDTO;
import tn.sncft.trino.notification.dto.ResultatAbonnement;
import tn.sncft.trino.notification.repo.AbonnementRepository;
import tn.sncft.trino.notification.repo.NotificationRepository;
import tn.sncft.trino.securite.IdentiteAbonne;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Ownership, which on these endpoints is the whole of authorisation.
 *
 * <p>They are public: there is no role to check and no principal to compare
 * against, so the only thing standing between one passenger and another's
 * subscription list is that every lookup carries the identity as part of the
 * query. These tests are what pin that.
 */
class ServiceAbonnementTest {

    private static final String JETON = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String JETON_AUTRE = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB";

    private AbonnementRepository abonnementRepository;
    private NotificationRepository notificationRepository;
    private ServiceAbonnement service;

    @BeforeEach
    void preparer() {
        abonnementRepository = mock(AbonnementRepository.class);
        notificationRepository = mock(NotificationRepository.class);
        service = new ServiceAbonnement(abonnementRepository, notificationRepository);
        when(abonnementRepository.save(any())).thenAnswer(invocation -> {
            Abonnement abonnement = invocation.getArgument(0);
            if (abonnement.getId() == null) {
                abonnement.setId(1L);
            }
            return abonnement;
        });
    }

    private static AbonnementCreateDTO requete(Set<CanalType> canaux, String email) {
        return new AbonnementCreateDTO(CibleType.COURSE, 42L, canaux, email);
    }

    @Test
    @DisplayName("un abonnement anonyme porte le jeton et aucun utilisateur")
    void abonnementAnonymePorteExactementUneIdentite() {
        when(abonnementRepository.findByJetonAnonymeAndCibleTypeAndCibleId(JETON, CibleType.COURSE, 42L))
                .thenReturn(Optional.empty());

        ResultatAbonnement resultat = service.enregistrer(
                IdentiteAbonne.deJeton(JETON), requete(EnumSet.of(CanalType.IN_APP), null));

        assertThat(resultat.cree()).isTrue();
        verify(abonnementRepository).save(org.mockito.ArgumentMatchers.argThat(abonnement ->
                JETON.equals(abonnement.getJetonAnonyme()) && abonnement.getUtilisateurId() == null));
    }

    @Test
    @DisplayName("un abonnement de compte porte l'utilisateur et aucun jeton")
    void abonnementDeCompatePorteExactementUneIdentite() {
        when(abonnementRepository.findByUtilisateurIdAndCibleTypeAndCibleId(7L, CibleType.COURSE, 42L))
                .thenReturn(Optional.empty());

        service.enregistrer(IdentiteAbonne.deCompte(7L), requete(EnumSet.of(CanalType.IN_APP), null));

        verify(abonnementRepository).save(org.mockito.ArgumentMatchers.argThat(abonnement ->
                abonnement.getJetonAnonyme() == null && Long.valueOf(7L).equals(abonnement.getUtilisateurId())));
    }

    /**
     * "Suivre ce train" is a button on a public page. It gets double-clicked, and
     * pressed again from another tab, and pressed by someone who now wants email
     * too. A 409 on each of those is a worse answer than updating the row -- and
     * the partial unique index would otherwise surface as a generic uniqueness
     * conflict.
     */
    @Test
    @DisplayName("se réabonner met à jour et ne recrée pas")
    void reabonnementMetAJour() {
        Abonnement existant = abonnement(JETON);
        existant.setCanaux(EnumSet.of(CanalType.IN_APP));
        when(abonnementRepository.findByJetonAnonymeAndCibleTypeAndCibleId(JETON, CibleType.COURSE, 42L))
                .thenReturn(Optional.of(existant));

        ResultatAbonnement resultat = service.enregistrer(
                IdentiteAbonne.deJeton(JETON),
                requete(EnumSet.of(CanalType.IN_APP, CanalType.EMAIL), "voyageur@exemple.tn"));

        assertThat(resultat.cree()).isFalse();
        assertThat(existant.getCanaux()).containsExactlyInAnyOrder(CanalType.IN_APP, CanalType.EMAIL);
        assertThat(existant.getEmail()).isEqualTo("voyageur@exemple.tn");
    }

    /**
     * The point of the whole module's scoping. The repository is only ever asked
     * for "this id AND this token", so a caller holding somebody else's id gets
     * nothing back -- and 404 rather than 403, which would confirm the id exists.
     */
    @Test
    @DisplayName("le jeton d'un autre ne supprime rien, et reçoit 404")
    void jetonDAutruiNeSupprimeRien() {
        when(abonnementRepository.findByIdAndJetonAnonyme(1L, JETON_AUTRE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.supprimer(IdentiteAbonne.deJeton(JETON_AUTRE), 1L))
                .isInstanceOf(RessourceIntrouvableException.class);

        verify(abonnementRepository, never()).delete(any());
    }

    @Test
    @DisplayName("le propriétaire supprime le sien")
    void proprietaireSupprime() {
        Abonnement sien = abonnement(JETON);
        when(abonnementRepository.findByIdAndJetonAnonyme(1L, JETON)).thenReturn(Optional.of(sien));

        service.supprimer(IdentiteAbonne.deJeton(JETON), 1L);

        verify(abonnementRepository).delete(sien);
    }

    @Test
    @DisplayName("une adresse vide est stockée nulle, pas comme chaîne vide")
    void emailVideEstNul() {
        when(abonnementRepository.findByJetonAnonymeAndCibleTypeAndCibleId(JETON, CibleType.COURSE, 42L))
                .thenReturn(Optional.empty());

        service.enregistrer(IdentiteAbonne.deJeton(JETON), requete(EnumSet.of(CanalType.IN_APP), "   "));

        verify(abonnementRepository).save(org.mockito.ArgumentMatchers.argThat(
                abonnement -> abonnement.getEmail() == null));
    }

    private static Abonnement abonnement(String jeton) {
        Abonnement abonnement = new Abonnement();
        abonnement.setId(1L);
        abonnement.setJetonAnonyme(jeton);
        abonnement.setCibleType(CibleType.COURSE);
        abonnement.setCibleId(42L);
        abonnement.setCanaux(EnumSet.of(CanalType.IN_APP));
        abonnement.setCreeAt(OffsetDateTime.now(ZoneOffset.UTC));
        return abonnement;
    }
}
