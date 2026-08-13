package tn.sncft.trino.notification.service;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.sncft.trino.commun.ConflitException;
import tn.sncft.trino.commun.RessourceIntrouvableException;
import tn.sncft.trino.iam.dto.UtilisateurDTO;
import tn.sncft.trino.iam.service.UtilisateurService;
import tn.sncft.trino.notification.domaine.CanalType;
import tn.sncft.trino.notification.domaine.Evenement;
import tn.sncft.trino.notification.domaine.RegleAlerte;
import tn.sncft.trino.notification.dto.RegleAlerteCreateDTO;
import tn.sncft.trino.notification.dto.RegleAlerteDTO;
import tn.sncft.trino.notification.dto.RegleAlerteUpdateDTO;
import tn.sncft.trino.notification.repo.RegleAlerteRepository;
import tn.sncft.trino.securite.DetailsUtilisateur;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The administration side of notifications: which events are worth alerting on.
 *
 * <p>{@code ADMINISTRATEUR} only, with {@code @PreAuthorize} here <em>and</em> a
 * URL rule in {@code ConfigurationSecurite} -- invariant 9, both halves. The URL
 * rule is what matters for {@code POST} and {@code PATCH}, whose bodies are
 * validated: {@code @Valid} runs during controller argument resolution, before
 * the proxy behind this annotation exists, so without it a forbidden caller
 * sending a malformed payload would be told their payload was malformed on an
 * endpoint they were never allowed to touch. This annotation stays as defence in
 * depth for calls that never pass through the filter chain.
 */
@Service
@Transactional
public class ServiceRegleAlerte {

    private final RegleAlerteRepository regleAlerteRepository;
    private final UtilisateurService utilisateurService;

    public ServiceRegleAlerte(RegleAlerteRepository regleAlerteRepository,
                              UtilisateurService utilisateurService) {
        this.regleAlerteRepository = regleAlerteRepository;
        this.utilisateurService = utilisateurService;
    }

    @PreAuthorize("hasRole('ADMINISTRATEUR')")
    @Transactional(readOnly = true)
    public List<RegleAlerteDTO> lister() {
        // Enum declaration order, not the alphabetical order of the stored
        // varchar: the console's own labels are written in this order, and a
        // list that disagrees with them reads as arbitrary.
        return regleAlerteRepository.findAllByOrderByIdAsc().stream()
                .sorted(Comparator.comparing((RegleAlerte regle) -> regle.getEvenement().ordinal())
                        .thenComparing(RegleAlerte::getId))
                .map(this::versDTO)
                .toList();
    }

    @PreAuthorize("hasRole('ADMINISTRATEUR')")
    public RegleAlerteDTO creer(RegleAlerteCreateDTO requete) {
        verifierSeuil(requete.evenement(), requete.seuilMin());

        RegleAlerte regle = new RegleAlerte();
        regle.setEvenement(requete.evenement());
        regle.setSeuilMin(requete.seuilMin());
        regle.setGraviteMin(requete.graviteMin());
        regle.setCanaux(copie(requete.canaux()));
        regle.setActif(requete.actif() == null || requete.actif());
        regle.setModifiePar(administrateurCourant());
        return versDTO(regleAlerteRepository.save(regle));
    }

    /**
     * Partial update: an absent field is unchanged.
     *
     * <p>{@code seuilMin} is the one field a null cannot clear, because
     * {@code chk_regle_seuil} forbids a {@code RETARD_SEUIL} rule without one.
     * Treating absent as "leave it" is the only reading that lets an
     * administrator flip {@code actif} on a delay rule without restating its
     * threshold.
     */
    @PreAuthorize("hasRole('ADMINISTRATEUR')")
    public RegleAlerteDTO mettreAJour(Long id, RegleAlerteUpdateDTO requete) {
        RegleAlerte regle = regleAlerteRepository.findById(id)
                .orElseThrow(() -> new RessourceIntrouvableException("Règle d'alerte " + id + " introuvable."));

        if (requete.seuilMin() != null) {
            verifierSeuil(regle.getEvenement(), requete.seuilMin());
            regle.setSeuilMin(requete.seuilMin());
        }
        // The flag wins over the value: a request that says "clear it" and also
        // carries a severity is contradictory, and clearing is the intent that
        // cannot be expressed any other way.
        if (Boolean.TRUE.equals(requete.effacerGraviteMin())) {
            regle.setGraviteMin(null);
        } else if (requete.graviteMin() != null) {
            regle.setGraviteMin(requete.graviteMin());
        }
        if (requete.canaux() != null) {
            regle.setCanaux(copie(requete.canaux()));
        }
        if (requete.actif() != null) {
            regle.setActif(requete.actif());
        }
        regle.setModifiePar(administrateurCourant());
        return versDTO(regleAlerteRepository.save(regle));
    }

    /**
     * Refuses the two shapes {@code chk_regle_seuil} would reject, with a
     * message saying which. Letting the constraint fire instead produces
     * {@code 409 CONFLIT} reading "violates a uniqueness constraint", which is
     * both the wrong status and the wrong story.
     */
    private static void verifierSeuil(Evenement evenement, Short seuilMin) {
        if (evenement == Evenement.RETARD_SEUIL && seuilMin == null) {
            throw new ConflitException("Une règle RETARD_SEUIL doit porter un seuil en minutes.");
        }
        if (evenement != Evenement.RETARD_SEUIL && seuilMin != null) {
            throw new ConflitException("Un seuil en minutes n'a de sens que pour l'événement RETARD_SEUIL.");
        }
    }

    /**
     * The administrator making the change, from the security context.
     *
     * <p>Never from the request body. A rule that records who last touched it is
     * only worth recording if the subject cannot choose the name.
     */
    private Long administrateurCourant() {
        Authentication authentification = SecurityContextHolder.getContext().getAuthentication();
        if (authentification != null && authentification.getPrincipal() instanceof DetailsUtilisateur details) {
            return details.getUtilisateur().id();
        }
        return null;
    }

    private RegleAlerteDTO versDTO(RegleAlerte regle) {
        String nom = regle.getModifiePar() == null
                ? null
                : utilisateurService.trouverActifParId(regle.getModifiePar())
                        .map(UtilisateurDTO::nom)
                        .orElse(null);
        return new RegleAlerteDTO(
                regle.getId(),
                regle.getEvenement(),
                regle.getSeuilMin(),
                regle.getGraviteMin(),
                copie(regle.getCanaux()),
                regle.isActif(),
                regle.getModifiePar(),
                nom);
    }

    private static Set<CanalType> copie(Set<CanalType> canaux) {
        Set<CanalType> copie = EnumSet.noneOf(CanalType.class);
        copie.addAll(canaux);
        return copie;
    }
}
