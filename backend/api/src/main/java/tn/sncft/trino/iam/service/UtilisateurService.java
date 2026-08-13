package tn.sncft.trino.iam.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.sncft.trino.commun.AuthentificationEchoueeException;
import tn.sncft.trino.commun.ConflitException;
import tn.sncft.trino.commun.PageableUtils;
import tn.sncft.trino.commun.RessourceIntrouvableException;
import tn.sncft.trino.iam.domaine.RefreshToken;
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

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * Orchestrates login, refresh and logout, and exposes the read-only
 * administration lookups used by UtilisateurController.
 */
@Service
@Transactional
public class UtilisateurService {

    private final UtilisateurRepository utilisateurRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JetonService jetonService;
    private final JournalService journalService;
    private final BCryptPasswordEncoder motDePasseEncoder;
    private final GenerateurMotDePasse generateurMotDePasse;

    public UtilisateurService(UtilisateurRepository utilisateurRepository,
                               RefreshTokenRepository refreshTokenRepository,
                               JetonService jetonService,
                               JournalService journalService,
                               BCryptPasswordEncoder motDePasseEncoder,
                               GenerateurMotDePasse generateurMotDePasse) {
        this.utilisateurRepository = utilisateurRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jetonService = jetonService;
        this.journalService = journalService;
        this.motDePasseEncoder = motDePasseEncoder;
        this.generateurMotDePasse = generateurMotDePasse;
    }

    /**
     * The lookup is normalised the same way {@link #creer} normalises what it
     * stores. Without that, an account created as {@code Prenom.Nom@SNCFT.tn}
     * is stored lowercased and can never be logged into with the address the
     * administrator typed and handed over -- and every attempt lands in the
     * journal with a null {@code utilisateurId}, i.e. an audit trail claiming
     * the email matches no account when it does.
     *
     * <p>The journal keeps the address exactly as it was typed: what was
     * attempted is the fact being recorded, not what it normalises to.
     */
    public LoginResponseDTO login(LoginRequestDTO requete, HttpServletRequest httpRequete) {
        Utilisateur utilisateur = utilisateurRepository.findByEmail(normaliserEmail(requete.email()))
                .orElse(null);
        if (utilisateur == null || !utilisateur.isActif()
                || !motDePasseEncoder.matches(requete.motDePasse(), utilisateur.getMotDePasseHash())) {
            journalService.enregistrer(requete.email(), null, false, httpRequete);
            throw new AuthentificationEchoueeException("Email ou mot de passe invalide.");
        }
        journalService.enregistrer(requete.email(), utilisateur, true, httpRequete);
        return emettreNouvellePaire(utilisateur);
    }

    /** One definition of "the same email", shared by {@link #login} and {@link #creer}. */
    private static String normaliserEmail(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }

    /**
     * No rotation (per phase rules): the presented refresh token stays valid
     * and is returned unchanged, only a new access token is issued. Rotating
     * it here would invalidate concurrent refreshes (e.g. two tabs, or a
     * client retrying a request while the token also expires) and force an
     * unnecessary re-login.
     *
     * Resolves the token itself (body takes precedence over the httpOnly
     * cookie, which browser clients rely on since they cannot read the
     * cookie's value to put it in the body) so the controller stays pure
     * request/response mapping.
     */
    public LoginResponseDTO rafraichir(String refreshTokenCorps, String refreshTokenCookie) {
        String refreshTokenBrut = refreshTokenCorps != null && !refreshTokenCorps.isBlank()
                ? refreshTokenCorps
                : refreshTokenCookie;
        if (refreshTokenBrut == null || refreshTokenBrut.isBlank()) {
            throw new AuthentificationEchoueeException("Jeton de rafraîchissement manquant.");
        }
        String hash = jetonService.hacherRefreshToken(refreshTokenBrut);
        RefreshToken tokenExistant = refreshTokenRepository.findByTokenHash(hash).orElse(null);
        if (tokenExistant == null || tokenExistant.isRevoque()
                || tokenExistant.getExpireAt().isBefore(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw new AuthentificationEchoueeException("Jeton de rafraîchissement invalide ou expiré.");
        }
        Utilisateur utilisateur = utilisateurRepository.findById(tokenExistant.getUtilisateurId())
                .filter(Utilisateur::isActif)
                .orElseThrow(() -> new AuthentificationEchoueeException("Utilisateur introuvable ou inactif."));
        String accessToken = jetonService.genererAccessToken(utilisateur);
        return new LoginResponseDTO(accessToken, refreshTokenBrut, versDTO(utilisateur));
    }

    public void deconnexion(String refreshTokenBrut) {
        String hash = jetonService.hacherRefreshToken(refreshTokenBrut);
        refreshTokenRepository.findByTokenHash(hash).ifPresent(token -> {
            token.setRevoque(true);
            refreshTokenRepository.save(token);
        });
    }

    @Transactional(readOnly = true)
    public UtilisateurDTO moi(String email) {
        return versDTO(utilisateurRepository.findByEmail(email)
                .orElseThrow(() -> new RessourceIntrouvableException("Utilisateur introuvable pour l'email " + email)));
    }

    @PreAuthorize("hasRole('ADMINISTRATEUR')")
    @Transactional(readOnly = true)
    public Page<UtilisateurDTO> lister(int page, int taille) {
        return utilisateurRepository.findAll(PageableUtils.de(page, taille)).map(this::versDTO);
    }

    /**
     * The account behind an id, or empty when there is none or it is
     * deactivated. Added in phase 8 for the notification engine, which has to
     * resolve a subscriber before it addresses anything to them.
     *
     * <p>Not role-gated, unlike {@link #trouverParId} beneath it, and the
     * distinction is deliberate: this is a module-to-module lookup with no
     * controller behind it and no way to reach it over HTTP. Gating it would
     * only mean the engine failed with {@code AccessDeniedException} on its own
     * async thread, where no security context exists and nothing would surface
     * the error.
     *
     * <p>Folding "exists" and "is active" into one answer is what stops a
     * deactivated account from still being notified. {@code abonnement} rows
     * survive deactivation because a user row is never deleted (decision 11), so
     * without this check the subscriptions of a closed account keep producing
     * mail indefinitely.
     */
    @Transactional(readOnly = true)
    public Optional<UtilisateurDTO> trouverActifParId(Long id) {
        return utilisateurRepository.findById(id)
                .filter(Utilisateur::isActif)
                .map(this::versDTO);
    }

    @PreAuthorize("hasRole('ADMINISTRATEUR')")
    @Transactional(readOnly = true)
    public UtilisateurDTO trouverParId(Long id) {
        return versDTO(utilisateurRepository.findById(id)
                .orElseThrow(() -> new RessourceIntrouvableException("Utilisateur introuvable pour l'id " + id)));
    }

    @PreAuthorize("hasRole('ADMINISTRATEUR')")
    public UtilisateurCreeDTO creer(UtilisateurCreateDTO requete) {
        String email = normaliserEmail(requete.email());
        if (utilisateurRepository.findByEmail(email).isPresent()) {
            throw new ConflitException("Un compte existe déjà pour l'email " + email + ".");
        }
        String motDePasse = generateurMotDePasse.generer();

        Utilisateur utilisateur = new Utilisateur();
        utilisateur.setEmail(email);
        utilisateur.setNom(requete.nom());
        utilisateur.setRole(requete.role());
        utilisateur.setMotDePasseHash(motDePasseEncoder.encode(motDePasse));
        utilisateur.setActif(true);
        utilisateur.setCreeAt(OffsetDateTime.now(ZoneOffset.UTC));
        utilisateur = utilisateurRepository.save(utilisateur);

        return versDTOCree(utilisateur, motDePasse);
    }

    /**
     * PATCH semantics: only non-null fields of {@code requete} are applied.
     *
     * <p>Deactivating an account does not delete it -- the connection journal
     * references the row by id, and an audit trail does not get holes.
     *
     * <p>It takes effect on the next request, not after the access token
     * expires: {@link tn.sncft.trino.securite.FiltreJwt} re-reads the account
     * on every authenticated request and leaves the context anonymous when
     * {@code actif} is false, so an already-issued token stops working
     * immediately. {@link #login} and {@link #rafraichir} refuse it too. The
     * cost of that guarantee is one lookup per request, which is why it is
     * worth stating rather than rediscovering.
     */
    @PreAuthorize("hasRole('ADMINISTRATEUR')")
    public UtilisateurDTO mettreAJour(Long id, UtilisateurUpdateDTO requete) {
        Utilisateur utilisateur = utilisateurRepository.findById(id)
                .orElseThrow(() -> new RessourceIntrouvableException("Utilisateur introuvable pour l'id " + id));

        String emailAppelant = SecurityContextHolder.getContext().getAuthentication() != null
                ? SecurityContextHolder.getContext().getAuthentication().getName()
                : null;
        if (emailAppelant != null && emailAppelant.equalsIgnoreCase(utilisateur.getEmail())) {
            if (requete.actif() != null && !requete.actif()) {
                throw new ConflitException("Vous ne pouvez pas désactiver votre propre compte.");
            }
            if (requete.role() != null && requete.role() != Role.ADMINISTRATEUR) {
                throw new ConflitException("Vous ne pouvez pas retirer son rôle ADMINISTRATEUR à votre propre compte.");
            }
        }

        if (requete.nom() != null) {
            utilisateur.setNom(requete.nom());
        }
        if (requete.role() != null) {
            utilisateur.setRole(requete.role());
        }
        if (requete.actif() != null) {
            utilisateur.setActif(requete.actif());
        }
        return versDTO(utilisateurRepository.save(utilisateur));
    }

    @PreAuthorize("hasRole('ADMINISTRATEUR')")
    public UtilisateurCreeDTO reinitialiserMotDePasse(Long id) {
        Utilisateur utilisateur = utilisateurRepository.findById(id)
                .orElseThrow(() -> new RessourceIntrouvableException("Utilisateur introuvable pour l'id " + id));
        String motDePasse = generateurMotDePasse.generer();
        utilisateur.setMotDePasseHash(motDePasseEncoder.encode(motDePasse));
        utilisateur = utilisateurRepository.save(utilisateur);
        return versDTOCree(utilisateur, motDePasse);
    }

    private UtilisateurCreeDTO versDTOCree(Utilisateur utilisateur, String motDePasseInitial) {
        return new UtilisateurCreeDTO(
                utilisateur.getId(),
                utilisateur.getEmail(),
                utilisateur.getNom(),
                utilisateur.getRole(),
                utilisateur.isActif(),
                motDePasseInitial
        );
    }

    private LoginResponseDTO emettreNouvellePaire(Utilisateur utilisateur) {
        String accessToken = jetonService.genererAccessToken(utilisateur);
        String refreshTokenBrut = jetonService.genererRefreshTokenBrut();

        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setUtilisateurId(utilisateur.getId());
        refreshToken.setTokenHash(jetonService.hacherRefreshToken(refreshTokenBrut));
        OffsetDateTime maintenant = OffsetDateTime.now(ZoneOffset.UTC);
        refreshToken.setCreeAt(maintenant);
        refreshToken.setExpireAt(maintenant.plusDays(jetonService.getRefreshExpirationDays()));
        refreshToken.setRevoque(false);
        refreshTokenRepository.save(refreshToken);

        return new LoginResponseDTO(accessToken, refreshTokenBrut, versDTO(utilisateur));
    }

    private UtilisateurDTO versDTO(Utilisateur utilisateur) {
        return new UtilisateurDTO(
                utilisateur.getId(),
                utilisateur.getEmail(),
                utilisateur.getNom(),
                utilisateur.getRole(),
                utilisateur.isActif()
        );
    }
}
