package tn.sncft.trino.circulation.domaine;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import tn.sncft.trino.referentiel.domaine.Gare;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * One ingested GPS ping. Append-only history for reports; the live position of
 * a course is held in memory (EtatCirculationStore, phase 3). Never query this
 * table on the hot path.
 *
 * <p>{@code etaSuivante} stays null in phase 2: ETA is speed-based and belongs
 * to the delay engine (decision 6). Ingestion does not predict.
 */
@Entity
@Table(name = "position_course")
public class PositionCourse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @Column(nullable = false)
    private OffsetDateTime horodatage;

    @Column(nullable = false, precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(nullable = false, precision = 9, scale = 6)
    private BigDecimal longitude;

    @Column(name = "vitesse_kmh")
    private Short vitesseKmh;

    @Column(name = "avancement_km", precision = 7, scale = 2)
    private BigDecimal avancementKm;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "gare_precedente_id")
    private Gare garePrecedente;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "gare_suivante_id")
    private Gare gareSuivante;

    @Column(name = "eta_suivante")
    private OffsetDateTime etaSuivante;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Course getCourse() {
        return course;
    }

    public void setCourse(Course course) {
        this.course = course;
    }

    public OffsetDateTime getHorodatage() {
        return horodatage;
    }

    public void setHorodatage(OffsetDateTime horodatage) {
        this.horodatage = horodatage;
    }

    public BigDecimal getLatitude() {
        return latitude;
    }

    public void setLatitude(BigDecimal latitude) {
        this.latitude = latitude;
    }

    public BigDecimal getLongitude() {
        return longitude;
    }

    public void setLongitude(BigDecimal longitude) {
        this.longitude = longitude;
    }

    public Short getVitesseKmh() {
        return vitesseKmh;
    }

    public void setVitesseKmh(Short vitesseKmh) {
        this.vitesseKmh = vitesseKmh;
    }

    public BigDecimal getAvancementKm() {
        return avancementKm;
    }

    public void setAvancementKm(BigDecimal avancementKm) {
        this.avancementKm = avancementKm;
    }

    public Gare getGarePrecedente() {
        return garePrecedente;
    }

    public void setGarePrecedente(Gare garePrecedente) {
        this.garePrecedente = garePrecedente;
    }

    public Gare getGareSuivante() {
        return gareSuivante;
    }

    public void setGareSuivante(Gare gareSuivante) {
        this.gareSuivante = gareSuivante;
    }

    public OffsetDateTime getEtaSuivante() {
        return etaSuivante;
    }

    public void setEtaSuivante(OffsetDateTime etaSuivante) {
        this.etaSuivante = etaSuivante;
    }
}
