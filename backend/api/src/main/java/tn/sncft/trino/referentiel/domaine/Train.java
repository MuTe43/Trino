package tn.sncft.trino.referentiel.domaine;

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

/**
 * Rolling stock. Deliberately has NO status and NO delay: a Course (a dated
 * run of a train on a line) carries status and delay, not the train itself.
 */
@Entity
@Table(name = "train")
public class Train {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 20)
    private String numero;

    @Column(length = 120)
    private String nom;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TypeTrain type;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ligne_id")
    private Ligne ligne;

    @Column
    private Short capacite;

    @Column(name = "vitesse_max_kmh")
    private Short vitesseMaxKmh;

    @Column(nullable = false)
    private boolean actif = true;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getNumero() {
        return numero;
    }

    public void setNumero(String numero) {
        this.numero = numero;
    }

    public String getNom() {
        return nom;
    }

    public void setNom(String nom) {
        this.nom = nom;
    }

    public TypeTrain getType() {
        return type;
    }

    public void setType(TypeTrain type) {
        this.type = type;
    }

    public Ligne getLigne() {
        return ligne;
    }

    public void setLigne(Ligne ligne) {
        this.ligne = ligne;
    }

    public Short getCapacite() {
        return capacite;
    }

    public void setCapacite(Short capacite) {
        this.capacite = capacite;
    }

    public Short getVitesseMaxKmh() {
        return vitesseMaxKmh;
    }

    public void setVitesseMaxKmh(Short vitesseMaxKmh) {
        this.vitesseMaxKmh = vitesseMaxKmh;
    }

    public boolean isActif() {
        return actif;
    }

    public void setActif(boolean actif) {
        this.actif = actif;
    }
}
