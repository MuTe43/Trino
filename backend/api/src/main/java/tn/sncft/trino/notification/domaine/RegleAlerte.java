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
import tn.sncft.trino.exploitation.domaine.Gravite;

import java.util.EnumSet;
import java.util.Set;

/**
 * What the administrator configures: which events are worth a notification, on
 * which channels, above which threshold. This is the <em>gérer les alertes</em>
 * use case, which had no implementation before this phase.
 *
 * <p>{@code canaux} here and {@code canaux} on {@link Abonnement} answer two
 * different questions -- what an event is allowed to use, and what a subscriber
 * wants -- and the engine emits on the intersection. The four rows V8 seeds
 * carry all four channels so that, out of the box, the choice belongs entirely
 * to the subscriber.
 *
 * <p>{@code modifiePar} is null until a human edits the row, and is a plain
 * column rather than a {@code @ManyToOne}: the module resolves accounts through
 * {@code UtilisateurService}.
 */
@Entity
@Table(name = "regle_alerte")
public class RegleAlerte {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "evenement", nullable = false, length = 20)
    private Evenement evenement;

    /**
     * Delay in minutes at or above which {@code RETARD_SEUIL} fires. Required
     * for that event and forbidden for the others
     * ({@code chk_regle_seuil}): a delay rule with no threshold fires on every
     * revision of every estimate.
     */
    @Column(name = "seuil_min")
    private Short seuilMin;

    /** Lowest severity worth notifying about. Null means every severity. */
    @Enumerated(EnumType.STRING)
    @Column(name = "gravite_min", length = 10)
    private Gravite graviteMin;

    @Convert(converter = ConvertisseurCanaux.class)
    @Column(name = "canaux", nullable = false, length = 80)
    private Set<CanalType> canaux = EnumSet.noneOf(CanalType.class);

    @Column(name = "actif", nullable = false)
    private boolean actif = true;

    @Column(name = "modifie_par")
    private Long modifiePar;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Evenement getEvenement() {
        return evenement;
    }

    public void setEvenement(Evenement evenement) {
        this.evenement = evenement;
    }

    public Short getSeuilMin() {
        return seuilMin;
    }

    public void setSeuilMin(Short seuilMin) {
        this.seuilMin = seuilMin;
    }

    public Gravite getGraviteMin() {
        return graviteMin;
    }

    public void setGraviteMin(Gravite graviteMin) {
        this.graviteMin = graviteMin;
    }

    public Set<CanalType> getCanaux() {
        return canaux;
    }

    public void setCanaux(Set<CanalType> canaux) {
        this.canaux = canaux;
    }

    public boolean isActif() {
        return actif;
    }

    public void setActif(boolean actif) {
        this.actif = actif;
    }

    public Long getModifiePar() {
        return modifiePar;
    }

    public void setModifiePar(Long modifiePar) {
        this.modifiePar = modifiePar;
    }
}
