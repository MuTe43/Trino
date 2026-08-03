package tn.sncft.trino.iam.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.sncft.trino.commun.AuthentificationEchoueeException;
import tn.sncft.trino.commun.PageableUtils;
import tn.sncft.trino.commun.RessourceIntrouvableException;
import tn.sncft.trino.iam.domaine.RefreshToken;
import tn.sncft.trino.iam.domaine.Utilisateur;
import tn.sncft.trino.iam.dto.LoginRequestDTO;
import tn.sncft.trino.iam.dto.LoginResponseDTO;
import tn.sncft.trino.iam.dto.UtilisateurDTO;
import tn.sncft.trino.iam.repo.RefreshTokenRepository;
import tn.sncft.trino.iam.repo.UtilisateurRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

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

    public UtilisateurService(UtilisateurRepository utilisateurRepository,
                               RefreshTokenRepository refreshTokenRepository,
                               JetonService jetonService,
                               JournalService journalService,
                               BCryptPasswordEncoder motDePasseEncoder) {
        this.utilisateurRepository = utilisateurRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jetonService = jetonService;
        this.journalService = journalService;
        this.motDePasseEncoder = motDePasseEncoder;
    }

    public LoginResponseDTO login(LoginRequestDTO requete, HttpServletRequest httpRequete) {
        Utilisateur utilisateur = utilisateurRepository.findByEmail(requete.email()).orElse(null);
        if (utilisateur == null || !utilisateur.isActif()
                || !motDePasseEncoder.matches(requete.motDePasse(), utilisateur.getMotDePasseHash())) {
            journalService.enregistrer(requete.email(), null, false, httpRequete);
            throw new AuthentificationEchoueeException("Email ou mot de passe invalide.");
        }
        journalService.enregistrer(requete.email(), utilisateur, true, httpRequete);
        return emettreNouvellePaire(utilisateur);
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

    @PreAuthorize("hasRole('ADMINISTRATEUR')")
    @Transactional(readOnly = true)
    public UtilisateurDTO trouverParId(Long id) {
        return versDTO(utilisateurRepository.findById(id)
                .orElseThrow(() -> new RessourceIntrouvableException("Utilisateur introuvable pour l'id " + id)));
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
