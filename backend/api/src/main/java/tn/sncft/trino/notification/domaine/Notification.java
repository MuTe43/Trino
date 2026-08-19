package tn.sncft.trino.notification.domaine;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * What was actually emitted -- one row per (subscription, channel), written
 * before the dispatch is attempted.
 *
 * <p>The row exists first and is updated afterwards, rather than being written
 * once the send succeeds. A channel that is down has to leave evidence: an
 * {@code ECHEC} row with {@code erreur} populated is the difference between "the
 * SMTP server was unreachable at 08:14" and a notification that silently never
 * happened.
 *
 * <p>{@code evenement} and {@code courseId} are stored because the deduplication
 * key is (subscription, event, course); without them the guard is unobservable
 * from outside the process, which is exactly what the acceptance check groups on.
 */
@Entity
@Table(name = "notification")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * When the row was written, as opposed to when its dispatch was attempted.
     *
     * <p>{@code envoyeAt} cannot answer "how long has this been waiting": it is
     * stamped at the start of the attempt, so it is null for a notification the
     * executor never picked up and misleading for one whose process died mid
     * dispatch. {@link tn.sncft.trino.notification.service.BalayeurNotification}
     * ages rows against this and nothing else reads it.
     */
    @Column(name = "cree_at", nullable = false)
    private OffsetDateTime creeAt = OffsetDateTime.now(java.time.ZoneOffset.UTC);

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "abonnement_id")
    private Abonnement abonnement;

    @Enumerated(EnumType.STRING)
    @Column(name = "evenement", nullable = false, length = 20)
    private Evenement evenement;

    /** Plain column: the course lives in another module (see {@link Abonnement}). */
    @Column(name = "course_id")
    private Long courseId;

    @Column(name = "destinataire", nullable = false, length = 160)
    private String destinataire;

    @Enumerated(EnumType.STRING)
    @Column(name = "canal", nullable = false, length = 10)
    private CanalType canal;

    @Column(name = "sujet", nullable = false, length = 200)
    private String sujet;

    @Column(name = "contenu", nullable = false, columnDefinition = "text")
    private String contenu;

    @Enumerated(EnumType.STRING)
    @Column(name = "statut", nullable = false, length = 10)
    private StatutNotification statut = StatutNotification.EN_ATTENTE;

    /** Null while {@code EN_ATTENTE}; stamped when the attempt completes, either way. */
    @Column(name = "envoye_at")
    private OffsetDateTime envoyeAt;

    @Column(name = "erreur", columnDefinition = "text")
    private String erreur;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public OffsetDateTime getCreeAt() {
        return creeAt;
    }

    public void setCreeAt(OffsetDateTime creeAt) {
        this.creeAt = creeAt;
    }

    public Abonnement getAbonnement() {
        return abonnement;
    }

    public void setAbonnement(Abonnement abonnement) {
        this.abonnement = abonnement;
    }

    public Evenement getEvenement() {
        return evenement;
    }

    public void setEvenement(Evenement evenement) {
        this.evenement = evenement;
    }

    public Long getCourseId() {
        return courseId;
    }

    public void setCourseId(Long courseId) {
        this.courseId = courseId;
    }

    public String getDestinataire() {
        return destinataire;
    }

    public void setDestinataire(String destinataire) {
        this.destinataire = destinataire;
    }

    public CanalType getCanal() {
        return canal;
    }

    public void setCanal(CanalType canal) {
        this.canal = canal;
    }

    public String getSujet() {
        return sujet;
    }

    public void setSujet(String sujet) {
        this.sujet = sujet;
    }

    public String getContenu() {
        return contenu;
    }

    public void setContenu(String contenu) {
        this.contenu = contenu;
    }

    public StatutNotification getStatut() {
        return statut;
    }

    public void setStatut(StatutNotification statut) {
        this.statut = statut;
    }

    public OffsetDateTime getEnvoyeAt() {
        return envoyeAt;
    }

    public void setEnvoyeAt(OffsetDateTime envoyeAt) {
        this.envoyeAt = envoyeAt;
    }

    public String getErreur() {
        return erreur;
    }

    public void setErreur(String erreur) {
        this.erreur = erreur;
    }
}
