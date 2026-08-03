package tn.sncft.trino.iam.domaine;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * Audit trail of login attempts, successful or not. Kept as a plain FK
 * column (utilisateurId), not a mapped relation, since it must survive
 * attempts where no matching user exists.
 */
@Entity
@Table(name = "journal_connexion")
public class JournalConnexion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "utilisateur_id")
    private Long utilisateurId;

    @Column(name = "email_tente", nullable = false, length = 160)
    private String emailTente;

    @Column(name = "adresse_ip", length = 45)
    private String adresseIp;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(nullable = false)
    private boolean succes;

    @Column(nullable = false)
    private OffsetDateTime horodatage;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUtilisateurId() {
        return utilisateurId;
    }

    public void setUtilisateurId(Long utilisateurId) {
        this.utilisateurId = utilisateurId;
    }

    public String getEmailTente() {
        return emailTente;
    }

    public void setEmailTente(String emailTente) {
        this.emailTente = emailTente;
    }

    public String getAdresseIp() {
        return adresseIp;
    }

    public void setAdresseIp(String adresseIp) {
        this.adresseIp = adresseIp;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public boolean isSucces() {
        return succes;
    }

    public void setSucces(boolean succes) {
        this.succes = succes;
    }

    public OffsetDateTime getHorodatage() {
        return horodatage;
    }

    public void setHorodatage(OffsetDateTime horodatage) {
        this.horodatage = horodatage;
    }
}
