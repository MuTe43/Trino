package tn.sncft.trino.iam.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tn.sncft.trino.iam.domaine.JournalConnexion;
import tn.sncft.trino.iam.domaine.Utilisateur;
import tn.sncft.trino.iam.repo.JournalConnexionRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Writes the login audit trail. Runs in its own transaction (REQUIRES_NEW)
 * so a failed login attempt is recorded even though the surrounding auth
 * flow then throws (and would otherwise roll everything back).
 */
@Service
public class JournalService {

    private final JournalConnexionRepository journalConnexionRepository;

    public JournalService(JournalConnexionRepository journalConnexionRepository) {
        this.journalConnexionRepository = journalConnexionRepository;
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
}
