package tn.sncft.trino.iam.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import tn.sncft.trino.commun.ConflitException;
import tn.sncft.trino.iam.domaine.Role;
import tn.sncft.trino.iam.domaine.Utilisateur;
import tn.sncft.trino.iam.dto.LoginRequestDTO;
import tn.sncft.trino.iam.dto.LoginResponseDTO;
import tn.sncft.trino.iam.dto.UtilisateurCreateDTO;
import tn.sncft.trino.iam.dto.UtilisateurCreeDTO;
import tn.sncft.trino.iam.dto.UtilisateurDTO;
import tn.sncft.trino.iam.dto.UtilisateurUpdateDTO;
import tn.sncft.trino.iam.repo.RefreshTokenRepository;
import tn.sncft.trino.iam.repo.UtilisateurRepository;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Account creation, PATCH self-lockout guard, and password re-issue.
 *
 * <p>Uses a real {@link BCryptPasswordEncoder} and a real
 * {@link GenerateurMotDePasse} rather than mocking either -- the point of
 * these tests is that the plaintext returned once really verifies against
 * the hash that was actually persisted, and that two generations differ.
 * {@code @PreAuthorize} is not exercised here (an AOP proxy that only exists
 * in a Spring context); the role gate is pinned end to end by
 * {@code AdminSecuriteTest}.
 */
class UtilisateurServiceTest {

    private static final String EMAIL_APPELANT = "admin@sncft.tn";

    private UtilisateurRepository utilisateurRepository;
    private BCryptPasswordEncoder motDePasseEncoder;
    private UtilisateurService service;

    @BeforeEach
    void preparer() {
        utilisateurRepository = mock(UtilisateurRepository.class);
        RefreshTokenRepository refreshTokenRepository = mock(RefreshTokenRepository.class);
        JetonService jetonService = mock(JetonService.class);
        JournalService journalService = mock(JournalService.class);
        motDePasseEncoder = new BCryptPasswordEncoder();
        GenerateurMotDePasse generateurMotDePasse = new GenerateurMotDePasse();

        service = new UtilisateurService(utilisateurRepository, refreshTokenRepository, jetonService,
                journalService, motDePasseEncoder, generateurMotDePasse);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(EMAIL_APPELANT, "", List.of()));
    }

    @AfterEach
    void nettoyer() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("creer retourne le mot de passe en clair une fois, et son hash BCrypt vérifie")
    void creerRetourneLeMotDePasseUneFoisEtSonHashVerifie() {
        when(utilisateurRepository.findByEmail("nouveau@sncft.tn")).thenReturn(Optional.empty());
        when(utilisateurRepository.save(any(Utilisateur.class))).thenAnswer(invocation -> {
            Utilisateur utilisateur = invocation.getArgument(0);
            utilisateur.setId(5L);
            return utilisateur;
        });

        UtilisateurCreeDTO resultat = service.creer(
                new UtilisateurCreateDTO(" Nouveau@Sncft.tn ", "Nouveau Nom", Role.AGENT_CIRCULATION));

        assertNotNull(resultat.motDePasseInitial());
        assertEquals("nouveau@sncft.tn", resultat.email(), "l'email est nettoyé (trim + minuscules)");
        assertTrue(resultat.actif());

        ArgumentCaptor<Utilisateur> capture = ArgumentCaptor.forClass(Utilisateur.class);
        org.mockito.Mockito.verify(utilisateurRepository).save(capture.capture());
        assertTrue(motDePasseEncoder.matches(resultat.motDePasseInitial(), capture.getValue().getMotDePasseHash()),
                "le mot de passe en clair retourné doit vérifier contre le hash persisté");
    }

    @Test
    @DisplayName("creer avec un email déjà pris rend CONFLIT")
    void creerAvecEmailExistantRendConflit() {
        when(utilisateurRepository.findByEmail(anyString())).thenReturn(Optional.of(new Utilisateur()));

        assertThrows(ConflitException.class, () -> service.creer(
                new UtilisateurCreateDTO("existe@sncft.tn", "Quelqu'un", Role.VOYAGEUR)));
    }

    @Test
    @DisplayName("un administrateur ne peut pas désactiver son propre compte")
    void autoDesactivationRendConflit() {
        Utilisateur cible = cible(1L, EMAIL_APPELANT, Role.ADMINISTRATEUR, true);
        when(utilisateurRepository.findById(1L)).thenReturn(Optional.of(cible));

        assertThrows(ConflitException.class,
                () -> service.mettreAJour(1L, new UtilisateurUpdateDTO(null, null, false)));
    }

    @Test
    @DisplayName("un administrateur ne peut pas se retirer son propre rôle ADMINISTRATEUR")
    void autoRetraitDeRoleRendConflit() {
        Utilisateur cible = cible(1L, EMAIL_APPELANT, Role.ADMINISTRATEUR, true);
        when(utilisateurRepository.findById(1L)).thenReturn(Optional.of(cible));

        assertThrows(ConflitException.class,
                () -> service.mettreAJour(1L, new UtilisateurUpdateDTO(null, Role.VOYAGEUR, null)));
    }

    @Test
    @DisplayName("un administrateur peut se réattribuer explicitement le rôle ADMINISTRATEUR")
    void autoAttributionAdministrateurResteAutorisee() {
        Utilisateur cible = cible(1L, EMAIL_APPELANT, Role.ADMINISTRATEUR, true);
        when(utilisateurRepository.findById(1L)).thenReturn(Optional.of(cible));
        when(utilisateurRepository.save(any(Utilisateur.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UtilisateurDTO resultat = service.mettreAJour(1L, new UtilisateurUpdateDTO(null, Role.ADMINISTRATEUR, null));

        assertEquals(Role.ADMINISTRATEUR, resultat.role());
    }

    @Test
    @DisplayName("désactiver un AUTRE administrateur reste autorisé")
    void desactiverUnAutreAdministrateurResteAutorise() {
        Utilisateur cible = cible(2L, "autre-admin@sncft.tn", Role.ADMINISTRATEUR, true);
        when(utilisateurRepository.findById(2L)).thenReturn(Optional.of(cible));
        when(utilisateurRepository.save(any(Utilisateur.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UtilisateurDTO resultat = service.mettreAJour(2L, new UtilisateurUpdateDTO(null, null, false));

        assertFalse(resultat.actif());
    }

    @Test
    @DisplayName("la réémission produit un mot de passe différent, dont le hash vérifie")
    void reemissionProduitUnMotDePasseDifferentDontLeHashVerifie() {
        Utilisateur cible = cible(3L, "agent@sncft.tn", Role.AGENT_CIRCULATION, true);
        String ancienMotDePasse = "ancien-mdp-XY12";
        cible.setMotDePasseHash(motDePasseEncoder.encode(ancienMotDePasse));
        when(utilisateurRepository.findById(3L)).thenReturn(Optional.of(cible));
        when(utilisateurRepository.save(any(Utilisateur.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UtilisateurCreeDTO resultat = service.reinitialiserMotDePasse(3L);

        assertNotEquals(ancienMotDePasse, resultat.motDePasseInitial());
        assertFalse(motDePasseEncoder.matches(ancienMotDePasse, cible.getMotDePasseHash()),
                "l'ancien mot de passe ne doit plus vérifier après réémission");
        assertTrue(motDePasseEncoder.matches(resultat.motDePasseInitial(), cible.getMotDePasseHash()),
                "le nouveau mot de passe en clair doit vérifier contre le nouveau hash persisté");
    }

    /**
     * The regression this pins: {@code creer} lowercases what it stores, so
     * {@code login} has to normalise the same way before looking up. Without
     * that, an account created as {@code Prenom.Nom@SNCFT.tn} could never be
     * logged into with the address the administrator typed and handed over --
     * and every attempt was journalled with a null utilisateurId, i.e. an audit
     * trail claiming the email matched no account while the account existed.
     * Found by review; it survives a green build because every seeded demo
     * account is already lowercase.
     */
    @Test
    @DisplayName("login retrouve un compte créé avec des majuscules ou des espaces")
    void loginNormaliseLEmailCommeCreer() {
        Utilisateur compte = cible(7L, "prenom.nom@sncft.tn", Role.AGENT_CIRCULATION, true);
        compte.setMotDePasseHash(motDePasseEncoder.encode("MotDePasseXY12"));
        // The repository only ever knows the normalised address, exactly as the
        // unique index on utilisateur.email holds it.
        when(utilisateurRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        when(utilisateurRepository.findByEmail("prenom.nom@sncft.tn")).thenReturn(Optional.of(compte));

        LoginResponseDTO resultat = service.login(
                new LoginRequestDTO(" Prenom.Nom@SNCFT.tn ", "MotDePasseXY12"),
                new MockHttpServletRequest());

        assertEquals("prenom.nom@sncft.tn", resultat.utilisateur().email());
    }

    private static Utilisateur cible(Long id, String email, Role role, boolean actif) {
        Utilisateur utilisateur = new Utilisateur();
        utilisateur.setId(id);
        utilisateur.setEmail(email);
        utilisateur.setNom("Test");
        utilisateur.setRole(role);
        utilisateur.setActif(actif);
        return utilisateur;
    }
}
