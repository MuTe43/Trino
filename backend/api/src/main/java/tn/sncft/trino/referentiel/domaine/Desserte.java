package tn.sncft.trino.referentiel.domaine;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * The theoretical stop pattern of a ligne: an ordered stop at a gare with
 * scheduled offsets in minutes. Theoretical times used to compute retard
 * come from here.
 */
@Entity
@Table(name = "desserte")
public class Desserte {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ligne_id", nullable = false)
    private Ligne ligne;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "gare_id", nullable = false)
    private Gare gare;

    @Column(nullable = false)
    private Short ordre;

    @Column(name = "pk_km", precision = 7, scale = 2)
    private BigDecimal pkKm;

    @Column(name = "offset_arrivee_min")
    private Short offsetArriveeMin;

    @Column(name = "offset_depart_min")
    private Short offsetDepartMin;

    /**
     * Schedule slack built into the segment arriving at this stop -- what lets
     * a late train make time back instead of carrying the same delay all the
     * way to the terminus. Zero on the first stop, which has no arriving
     * segment. Added in V4.
     */
    @Column(name = "marge_min", nullable = false)
    private Short margeMin = 0;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Ligne getLigne() {
        return ligne;
    }

    public void setLigne(Ligne ligne) {
        this.ligne = ligne;
    }

    public Gare getGare() {
        return gare;
    }

    public void setGare(Gare gare) {
        this.gare = gare;
    }

    public Short getOrdre() {
        return ordre;
    }

    public void setOrdre(Short ordre) {
        this.ordre = ordre;
    }

    public BigDecimal getPkKm() {
        return pkKm;
    }

    public void setPkKm(BigDecimal pkKm) {
        this.pkKm = pkKm;
    }

    public Short getOffsetArriveeMin() {
        return offsetArriveeMin;
    }

    public void setOffsetArriveeMin(Short offsetArriveeMin) {
        this.offsetArriveeMin = offsetArriveeMin;
    }

    public Short getOffsetDepartMin() {
        return offsetDepartMin;
    }

    public void setOffsetDepartMin(Short offsetDepartMin) {
        this.offsetDepartMin = offsetDepartMin;
    }

    public Short getMargeMin() {
        return margeMin;
    }

    public void setMargeMin(Short margeMin) {
        this.margeMin = margeMin;
    }
}
