package tn.sncft.trino.notification.domaine;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.Set;

/**
 * Who wants to hear about what.
 *
 * <p>Anonymous is the default case, not the exception: a passenger following a
 * train has no account, and requiring one to press "Suivre ce train" would put a
 * login in front of the single most common thing a passenger wants. The browser
 * holds a random {@code jeton_anonyme} and that is the whole identity.
 *
 * <p>Exactly one of {@code utilisateurId} and {@code jetonAnonyme} is set,
 * enforced by {@code chk_abonnement_identite} in V8. Neither means the row
 * belongs to nobody; both means one person accumulates an anonymous and an
 * account subscription to the same target and is notified twice for one event,
 * because a logged-in browser still carries the anonymous cookie.
 *
 * <p>{@code utilisateurId} is a plain column rather than a {@code @ManyToOne} to
 * {@code Utilisateur}: the notification module resolves accounts through
 * {@code UtilisateurService}, never by reaching into another module's mapping.
 * {@code RefreshToken} holds its owner the same way, for the same reason.
 */
@Entity
@Table(name = "abonnement")
public class Abonnement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "utilisateur_id")
    private Long utilisateurId;

    @Column(name = "jeton_anonyme", length = 64)
    private String jetonAnonyme;

    @Enumerated(EnumType.STRING)
    @Column(name = "cible_type", nullable = false, length = 10)
    private CibleType cibleType;

    @Column(name = "cible_id", nullable = false)
    private Long cibleId;

    @Convert(converter = ConvertisseurCanaux.class)
    @Column(name = "canaux", nullable = false, length = 80)
    private Set<CanalType> canaux = EnumSet.noneOf(CanalType.class);

    /**
     * Where {@code EMAIL} goes. Held on the subscription because the channel
     * sends long after the request that created it is gone; null for an account
     * subscription, which falls back to the account's own address.
     */
    @Column(name = "email", length = 160)
    private String email;

    @Column(name = "cree_at", nullable = false)
    private OffsetDateTime creeAt;

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

    public String getJetonAnonyme() {
        return jetonAnonyme;
    }

    public void setJetonAnonyme(String jetonAnonyme) {
        this.jetonAnonyme = jetonAnonyme;
    }

    public CibleType getCibleType() {
        return cibleType;
    }

    public void setCibleType(CibleType cibleType) {
        this.cibleType = cibleType;
    }

    public Long getCibleId() {
        return cibleId;
    }

    public void setCibleId(Long cibleId) {
        this.cibleId = cibleId;
    }

    public Set<CanalType> getCanaux() {
        return canaux;
    }

    public void setCanaux(Set<CanalType> canaux) {
        this.canaux = canaux;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public OffsetDateTime getCreeAt() {
        return creeAt;
    }

    public void setCreeAt(OffsetDateTime creeAt) {
        this.creeAt = creeAt;
    }
}
