package tn.sncft.trino.referentiel.domaine;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;

/**
 * A railway line. The theoretical stop pattern lives in {@link Desserte}.
 * The `trace` column stores an ordered polyline as a JSON array of
 * [lon,lat] pairs; parsing happens only in the service layer.
 */
@Entity
@Table(name = "ligne")
public class Ligne {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 20)
    private String code;

    @Column(nullable = false, length = 160)
    private String nom;

    @Column(name = "distance_km", precision = 7, scale = 2)
    private BigDecimal distanceKm;

    @Column(name = "vitesse_max_kmh")
    private Short vitesseMaxKmh;

    @Column(name = "temps_theorique_min")
    private Short tempsTheoriqueMin;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "trace", columnDefinition = "jsonb")
    private String trace;

    @Column(nullable = false)
    private boolean actif = true;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getNom() {
        return nom;
    }

    public void setNom(String nom) {
        this.nom = nom;
    }

    public BigDecimal getDistanceKm() {
        return distanceKm;
    }

    public void setDistanceKm(BigDecimal distanceKm) {
        this.distanceKm = distanceKm;
    }

    public Short getVitesseMaxKmh() {
        return vitesseMaxKmh;
    }

    public void setVitesseMaxKmh(Short vitesseMaxKmh) {
        this.vitesseMaxKmh = vitesseMaxKmh;
    }

    public Short getTempsTheoriqueMin() {
        return tempsTheoriqueMin;
    }

    public void setTempsTheoriqueMin(Short tempsTheoriqueMin) {
        this.tempsTheoriqueMin = tempsTheoriqueMin;
    }

    public String getTrace() {
        return trace;
    }

    public void setTrace(String trace) {
        this.trace = trace;
    }

    public boolean isActif() {
        return actif;
    }

    public void setActif(boolean actif) {
        this.actif = actif;
    }
}
