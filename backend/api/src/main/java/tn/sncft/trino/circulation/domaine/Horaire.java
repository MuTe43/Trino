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

import java.time.LocalTime;

/**
 * A departure slot in the standing timetable: this train leaves on this ligne,
 * in this direction, at this local time, every service day. GenerateurCourses
 * materialises one Course per active slot per day.
 *
 * <p>{@code heureDepart} is wall-clock time in Africa/Tunis, resolved to an
 * instant per service date. It is deliberately not a timestamptz: a timetable
 * says "the 06:00", not "the 05:00Z".
 */
@Entity
@Table(name = "horaire")
public class Horaire {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ligne_id", nullable = false)
    private Ligne ligne;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "train_id", nullable = false)
    private Train train;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private SensCourse sens;

    @Column(name = "heure_depart", nullable = false)
    private LocalTime heureDepart;

    @Column(nullable = false)
    private boolean actif = true;

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

    public Train getTrain() {
        return train;
    }

    public void setTrain(Train train) {
        this.train = train;
    }

    public SensCourse getSens() {
        return sens;
    }

    public void setSens(SensCourse sens) {
        this.sens = sens;
    }

    public LocalTime getHeureDepart() {
        return heureDepart;
    }

    public void setHeureDepart(LocalTime heureDepart) {
        this.heureDepart = heureDepart;
    }

    public boolean isActif() {
        return actif;
    }

    public void setActif(boolean actif) {
        this.actif = actif;
    }
}
