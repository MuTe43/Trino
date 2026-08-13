package tn.sncft.trino.notification.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.sncft.trino.commun.PageableUtils;
import tn.sncft.trino.commun.RessourceIntrouvableException;
import tn.sncft.trino.notification.domaine.Abonnement;
import tn.sncft.trino.notification.domaine.CanalType;
import tn.sncft.trino.notification.domaine.Notification;
import tn.sncft.trino.notification.dto.AbonnementCreateDTO;
import tn.sncft.trino.notification.dto.AbonnementDTO;
import tn.sncft.trino.notification.dto.NotificationDTO;
import tn.sncft.trino.notification.dto.ResultatAbonnement;
import tn.sncft.trino.notification.repo.AbonnementRepository;
import tn.sncft.trino.notification.repo.NotificationRepository;
import tn.sncft.trino.securite.IdentiteAbonne;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The passenger side of notifications: following something, unfollowing it, and
 * reading what arrived.
 *
 * <p>Every method takes an {@link IdentiteAbonne} and scopes on it. There is no
 * overload that takes an id alone -- a public endpoint whose only argument is a
 * row id is one refactor away from letting anybody read anybody's list, and this
 * module's endpoints are all public by design.
 *
 * <p>No {@code @PreAuthorize} anywhere here, and that is correct rather than an
 * omission: these are anonymous endpoints (invariant 9 governs role-gated
 * writes, and there is no role to gate on). The authorisation that does apply is
 * ownership, and it is expressed as a query parameter on every lookup.
 */
@Service
@Transactional
public class ServiceAbonnement {

    private final AbonnementRepository abonnementRepository;
    private final NotificationRepository notificationRepository;

    public ServiceAbonnement(AbonnementRepository abonnementRepository,
                             NotificationRepository notificationRepository) {
        this.abonnementRepository = abonnementRepository;
        this.notificationRepository = notificationRepository;
    }

    /**
     * Creates the subscription, or updates the one that already exists for this
     * identity and target.
     *
     * <p>Re-subscribing is not an error. "Suivre ce train" is a button on a
     * public page: it gets double-clicked, it gets pressed again from a second
     * tab, and it gets pressed by someone who wants to add email to a
     * subscription they made yesterday. A 409 on each of those is a worse answer
     * than doing the obvious thing -- and the partial unique indexes would
     * otherwise turn the second press into a constraint violation reported as a
     * generic uniqueness conflict.
     *
     * @return the subscription, and whether this call created it (201) or
     *         updated an existing one (200)
     */
    public ResultatAbonnement enregistrer(IdentiteAbonne identite, AbonnementCreateDTO requete) {
        Optional<Abonnement> existant = identite.estAnonyme()
                ? abonnementRepository.findByJetonAnonymeAndCibleTypeAndCibleId(
                        identite.jeton(), requete.cibleType(), requete.cibleId())
                : abonnementRepository.findByUtilisateurIdAndCibleTypeAndCibleId(
                        identite.utilisateurId(), requete.cibleType(), requete.cibleId());

        Abonnement abonnement = existant.orElseGet(Abonnement::new);
        boolean cree = existant.isEmpty();
        if (cree) {
            // Exactly one identity, matching chk_abonnement_identite. Set from
            // the resolved identity and never from the request body.
            abonnement.setUtilisateurId(identite.utilisateurId());
            abonnement.setJetonAnonyme(identite.jeton());
            abonnement.setCibleType(requete.cibleType());
            abonnement.setCibleId(requete.cibleId());
            abonnement.setCreeAt(OffsetDateTime.now(ZoneOffset.UTC));
        }
        abonnement.setCanaux(copie(requete.canaux()));
        // Null rather than "" when omitted, so an address is either present or
        // absent -- the engine tests it with isBlank() either way, but a null
        // column reads as "never given" where an empty string reads as "given,
        // and empty".
        abonnement.setEmail(requete.email() == null || requete.email().isBlank()
                ? null
                : requete.email().trim());

        return new ResultatAbonnement(versDTO(abonnementRepository.save(abonnement)), cree);
    }


    /**
     * Cancels one subscription, scoped by the caller's identity.
     *
     * <p>A subscription belonging to somebody else answers {@code 404}, not
     * {@code 403}: with an id in the path and no account behind the request,
     * distinguishing "not yours" from "does not exist" would confirm to an
     * enumerating caller which ids are real subscriptions.
     */
    public void supprimer(IdentiteAbonne identite, Long id) {
        Abonnement abonnement = (identite.estAnonyme()
                ? abonnementRepository.findByIdAndJetonAnonyme(id, identite.jeton())
                : abonnementRepository.findByIdAndUtilisateurId(id, identite.utilisateurId()))
                .orElseThrow(() -> new RessourceIntrouvableException("Abonnement " + id + " introuvable."));
        abonnementRepository.delete(abonnement);
    }

    @Transactional(readOnly = true)
    public List<AbonnementDTO> miennes(IdentiteAbonne identite) {
        List<Abonnement> abonnements = identite.estAnonyme()
                ? abonnementRepository.findByJetonAnonymeOrderByIdDesc(identite.jeton())
                : abonnementRepository.findByUtilisateurIdOrderByIdDesc(identite.utilisateurId());
        return abonnements.stream().map(ServiceAbonnement::versDTO).toList();
    }

    /**
     * The caller's notifications, newest first.
     *
     * <p>Ordered on {@code id}, not on {@code envoyeAt}: that column is null
     * while a dispatch is in flight, so ordering on it would float every
     * pending notification to one end of the list and shuffle it back once SMTP
     * answered. The id is monotonic and never null, and for rows this table
     * only ever appends it is the emission order.
     */
    @Transactional(readOnly = true)
    public Page<NotificationDTO> notifications(IdentiteAbonne identite, int page, int taille) {
        Pageable pageable = PageableUtils.de(page, taille, Sort.by(Sort.Direction.DESC, "id"));
        Page<Notification> notifications = identite.estAnonyme()
                ? notificationRepository.findByAbonnementJetonAnonyme(identite.jeton(), pageable)
                : notificationRepository.findByAbonnementUtilisateurId(identite.utilisateurId(), pageable);
        return notifications.map(ServiceAbonnement::versDTO);
    }

    private static AbonnementDTO versDTO(Abonnement abonnement) {
        return new AbonnementDTO(
                abonnement.getId(),
                abonnement.getCibleType(),
                abonnement.getCibleId(),
                copie(abonnement.getCanaux()),
                abonnement.getEmail(),
                abonnement.getCreeAt());
    }

    /**
     * A defensive copy in declaration order. Not {@code EnumSet.copyOf}, which
     * throws on an empty collection that is not already an {@code EnumSet} --
     * a trap worth stepping around rather than reasoning about at each call.
     */
    private static Set<CanalType> copie(Set<CanalType> canaux) {
        Set<CanalType> copie = EnumSet.noneOf(CanalType.class);
        copie.addAll(canaux);
        return copie;
    }

    private static NotificationDTO versDTO(Notification notification) {
        return new NotificationDTO(
                notification.getId(),
                notification.getEvenement(),
                notification.getCourseId(),
                notification.getCanal(),
                notification.getSujet(),
                notification.getContenu(),
                notification.getStatut(),
                notification.getEnvoyeAt());
    }

}
