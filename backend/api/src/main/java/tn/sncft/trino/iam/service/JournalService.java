package tn.sncft.trino.iam.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tn.sncft.trino.commun.PageableUtils;
import tn.sncft.trino.commun.PlageDates;
import tn.sncft.trino.iam.domaine.JournalConnexion;
import tn.sncft.trino.iam.domaine.Utilisateur;
import tn.sncft.trino.iam.dto.JournalConnexionDTO;
import tn.sncft.trino.iam.repo.JournalConnexionRepository;
import tn.sncft.trino.iam.repo.JournalConnexionSpecifications;
import tn.sncft.trino.iam.repo.UtilisateurRepository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Writes the login audit trail. Runs in its own transaction (REQUIRES_NEW)
 * so a failed login attempt is recorded even though the surrounding auth
 * flow then throws (and would otherwise roll everything back).
 */
@Service
public class JournalService {

    /** {@code du}/{@code au} are bucketed in local time (invariant 6). */
    private static final ZoneId ZONE_TUNIS = ZoneId.of("Africa/Tunis");

    private final JournalConnexionRepository journalConnexionRepository;
    private final UtilisateurRepository utilisateurRepository;

    public JournalService(JournalConnexionRepository journalConnexionRepository,
                           UtilisateurRepository utilisateurRepository) {
        this.journalConnexionRepository = journalConnexionRepository;
        this.utilisateurRepository = utilisateurRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enregistrer(String emailTente, Utilisateur utilisateurOuNull, boolean succes,
                             HttpServletRequest requete) {
        JournalConnexion journal = new JournalConnexion();
        journal.setEmailTente(emailTente);
        journal.setUtilisateurId(utilisateurOuNull != null ? utilisateurOuNull.getId() : null);
        journal.setSucces(succes);
        journal.setAdresseIp(requete.getRemoteAddr());
        journal.setUserAgent(requete.getHeader("User-Agent"));
        journal.setHorodatage(OffsetDateTime.now(ZoneOffset.UTC));
        journalConnexionRepository.save(journal);
    }

    /**
     * Console-facing read of the audit trail. Every filter is optional and
     * independent; {@code du}/{@code au} are bucketed in Africa/Tunis
     * (invariant 6): {@code du} is the start of that local day, {@code au}
     * is used as an exclusive upper bound at the start of the following
     * local day.
     */
    @PreAuthorize("hasRole('ADMINISTRATEUR')")
    @Transactional(readOnly = true)
    public Page<JournalConnexionDTO> consulter(Boolean succes, Long utilisateurId, LocalDate du, LocalDate au,
                                                int page, int taille) {
        PlageDates.verifierOptionnelle(du, au);
        OffsetDateTime debut = du != null ? du.atStartOfDay(ZONE_TUNIS).toOffsetDateTime() : null;
        OffsetDateTime finExclusive = au != null ? au.plusDays(1).atStartOfDay(ZONE_TUNIS).toOffsetDateTime() : null;

        Specification<JournalConnexion> specification = Specification
                .where(JournalConnexionSpecifications.succesEgal(succes))
                .and(JournalConnexionSpecifications.utilisateurIdEgal(utilisateurId))
                .and(JournalConnexionSpecifications.horodatageDepuis(debut))
                .and(JournalConnexionSpecifications.horodatageAvant(finExclusive));

        Pageable brut = PageableUtils.de(page, taille);
        Pageable pageable = PageRequest.of(brut.getPageNumber(), brut.getPageSize(),
                Sort.by(Sort.Order.desc("horodatage"), Sort.Order.desc("id")));

        Page<JournalConnexion> resultat = journalConnexionRepository.findAll(specification, pageable);

        List<Long> idsUtilisateurs = resultat.getContent().stream()
                .map(JournalConnexion::getUtilisateurId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, String> nomsParId = utilisateurRepository.findAllById(idsUtilisateurs).stream()
                .collect(Collectors.toMap(Utilisateur::getId, Utilisateur::getNom));

        return resultat.map(journal -> new JournalConnexionDTO(
                journal.getId(),
                journal.getUtilisateurId(),
                journal.getUtilisateurId() != null ? nomsParId.get(journal.getUtilisateurId()) : null,
                journal.getEmailTente(),
                journal.getAdresseIp(),
                journal.getUserAgent(),
                journal.isSucces(),
                journal.getHorodatage()
        ));
    }
}
