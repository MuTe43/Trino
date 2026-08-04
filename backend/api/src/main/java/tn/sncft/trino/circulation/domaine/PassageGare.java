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
 * One stop of one course. Carries the three times of api-contract section 4.5,
 * which are never conflated:
 *
 * <ul>
 *   <li>{@code *Theorique} -- the published timetable. Never changes.</li>
 *   <li>{@code *Estimee}   -- current best prediction. Starts equal to the
 *       theoretical time and is revised by the engine in phase 3.</li>
 *   <li>{@code *Reelle}    -- observed. Null until the train actually passes.</li>
 * </ul>
 *
 * <p>Arrival fields are null at the origin and departure fields at the
 * terminus: a train does not arrive at where it starts. Wherever a theoretical
 * time exists the estimate exists too, enforced by a check constraint.
 *
 * <p>{@code pkKm} and {@code margeMin} are per-course rather than read back
 * from the desserte, because a RETOUR course walks the desserte mirrored.
 */
@Entity
@Table(name = "passage_gare")
public class PassageGare {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "gare_id", nullable = false)
    private Gare gare;

    @Column(nullable = false)
    private Short ordre;

    @Column(name = "pk_km", nullable = false, precision = 7, scale = 2)
    private BigDecimal pkKm;

    @Column(name = "marge_min", nullable = false)
    private Short margeMin = 0;

    @Column(name = "arrivee_theorique")
    private OffsetDateTime arriveeTheorique;

    @Column(name = "depart_theorique")
    private OffsetDateTime departTheorique;

    @Column(name = "arrivee_estimee")
    private OffsetDateTime arriveeEstimee;

    @Column(name = "depart_estimee")
    private OffsetDateTime departEstimee;

    @Column(name = "arrivee_reelle")
    private OffsetDateTime arriveeReelle;

    @Column(name = "depart_reelle")
    private OffsetDateTime departReelle;

    @Column(length = 10)
    private String quai;

    @Column(name = "retard_min", nullable = false)
    private int retardMin;

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

    public Short getMargeMin() {
        return margeMin;
    }

    public void setMargeMin(Short margeMin) {
        this.margeMin = margeMin;
    }

    public OffsetDateTime getArriveeTheorique() {
        return arriveeTheorique;
    }

    public void setArriveeTheorique(OffsetDateTime arriveeTheorique) {
        this.arriveeTheorique = arriveeTheorique;
    }

    public OffsetDateTime getDepartTheorique() {
        return departTheorique;
    }

    public void setDepartTheorique(OffsetDateTime departTheorique) {
        this.departTheorique = departTheorique;
    }

    public OffsetDateTime getArriveeEstimee() {
        return arriveeEstimee;
    }

    public void setArriveeEstimee(OffsetDateTime arriveeEstimee) {
        this.arriveeEstimee = arriveeEstimee;
    }

    public OffsetDateTime getDepartEstimee() {
        return departEstimee;
    }

    public void setDepartEstimee(OffsetDateTime departEstimee) {
        this.departEstimee = departEstimee;
    }

    public OffsetDateTime getArriveeReelle() {
        return arriveeReelle;
    }

    public void setArriveeReelle(OffsetDateTime arriveeReelle) {
        this.arriveeReelle = arriveeReelle;
    }

    public OffsetDateTime getDepartReelle() {
        return departReelle;
    }

    public void setDepartReelle(OffsetDateTime departReelle) {
        this.departReelle = departReelle;
    }

    public String getQuai() {
        return quai;
    }

    public void setQuai(String quai) {
        this.quai = quai;
    }

    public int getRetardMin() {
        return retardMin;
    }

    public void setRetardMin(int retardMin) {
        this.retardMin = retardMin;
    }
}
