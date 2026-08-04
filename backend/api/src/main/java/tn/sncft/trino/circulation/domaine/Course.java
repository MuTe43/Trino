package tn.sncft.trino.circulation.domaine;

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
import tn.sncft.trino.referentiel.domaine.Ligne;
import tn.sncft.trino.referentiel.domaine.Train;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * One dated run of a train on a ligne. Status, delay and delay cause live
 * here, not on the Train (which is rolling stock and has neither).
 *
 * <p>Phase 2 only ever sets {@code avancementKm} and {@code dernierePositionAt}
 * after creation, from ingested positions. {@code statut}, {@code retardMin}
 * and {@code causeRetard} are written by the delay engine in phase 3.
 */
@Entity
@Table(name = "course")
public class Course {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "train_id", nullable = false)
    private Train train;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ligne_id", nullable = false)
    private Ligne ligne;

    @Column(name = "date_service", nullable = false)
    private LocalDate dateService;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private SensCourse sens;

    @Column(name = "depart_theorique", nullable = false)
    private OffsetDateTime departTheorique;

    @Column(name = "arrivee_theorique", nullable = false)
    private OffsetDateTime arriveeTheorique;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private StatutCourse statut = StatutCourse.A_QUAI;

    @Column(name = "retard_min", nullable = false)
    private int retardMin;

    @Enumerated(EnumType.STRING)
    @Column(name = "cause_retard", length = 30)
    private CauseRetard causeRetard;

    @Column(name = "avancement_km", nullable = false, precision = 7, scale = 2)
    private BigDecimal avancementKm = BigDecimal.ZERO;

    @Column(name = "derniere_position_at")
    private OffsetDateTime dernierePositionAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Train getTrain() {
        return train;
    }

    public void setTrain(Train train) {
        this.train = train;
    }

    public Ligne getLigne() {
        return ligne;
    }

    public void setLigne(Ligne ligne) {
        this.ligne = ligne;
    }

    public LocalDate getDateService() {
        return dateService;
    }

    public void setDateService(LocalDate dateService) {
        this.dateService = dateService;
    }

    public SensCourse getSens() {
        return sens;
    }

    public void setSens(SensCourse sens) {
        this.sens = sens;
    }

    public OffsetDateTime getDepartTheorique() {
        return departTheorique;
    }

    public void setDepartTheorique(OffsetDateTime departTheorique) {
        this.departTheorique = departTheorique;
    }

    public OffsetDateTime getArriveeTheorique() {
        return arriveeTheorique;
    }

    public void setArriveeTheorique(OffsetDateTime arriveeTheorique) {
        this.arriveeTheorique = arriveeTheorique;
    }

    public StatutCourse getStatut() {
        return statut;
    }

    public void setStatut(StatutCourse statut) {
        this.statut = statut;
    }

    public int getRetardMin() {
        return retardMin;
    }

    public void setRetardMin(int retardMin) {
        this.retardMin = retardMin;
    }

    public CauseRetard getCauseRetard() {
        return causeRetard;
    }

    public void setCauseRetard(CauseRetard causeRetard) {
        this.causeRetard = causeRetard;
    }

    public BigDecimal getAvancementKm() {
        return avancementKm;
    }

    public void setAvancementKm(BigDecimal avancementKm) {
        this.avancementKm = avancementKm;
    }

    public OffsetDateTime getDernierePositionAt() {
        return dernierePositionAt;
    }

    public void setDernierePositionAt(OffsetDateTime dernierePositionAt) {
        this.dernierePositionAt = dernierePositionAt;
    }
}
