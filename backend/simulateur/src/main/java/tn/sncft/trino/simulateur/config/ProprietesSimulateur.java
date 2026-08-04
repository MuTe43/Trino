package tn.sncft.trino.simulateur.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Simulator tuning. Every value is configuration -- in particular the API base
 * URL, which must never appear as a literal: the dev machine, the container
 * network and a future real deployment all address the API differently.
 */
@ConfigurationProperties(prefix = "trino")
public class ProprietesSimulateur {

    private Api api = new Api();
    private Ingestion ingestion = new Ingestion();
    private Simulateur simulateur = new Simulateur();

    public Api getApi() {
        return api;
    }

    public void setApi(Api api) {
        this.api = api;
    }

    public Ingestion getIngestion() {
        return ingestion;
    }

    public void setIngestion(Ingestion ingestion) {
        this.ingestion = ingestion;
    }

    public Simulateur getSimulateur() {
        return simulateur;
    }

    public void setSimulateur(Simulateur simulateur) {
        this.simulateur = simulateur;
    }

    public static class Api {
        private String baseUrl = "http://localhost:8080";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }

    public static class Ingestion {
        private String cle = "dev-key";

        public String getCle() {
            return cle;
        }

        public void setCle(String cle) {
            this.cle = cle;
        }
    }

    public static class Simulateur {
        private int tickSecondes = 5;
        private double acceleration = 1.0;
        private String heureDebut = "";
        private int ticksParRechargement = 60;
        private double partPerturbee = 0.28;

        public int getTickSecondes() {
            return tickSecondes;
        }

        public void setTickSecondes(int tickSecondes) {
            this.tickSecondes = tickSecondes;
        }

        public double getAcceleration() {
            return acceleration;
        }

        public void setAcceleration(double acceleration) {
            this.acceleration = acceleration;
        }

        public String getHeureDebut() {
            return heureDebut;
        }

        public void setHeureDebut(String heureDebut) {
            this.heureDebut = heureDebut;
        }

        public int getTicksParRechargement() {
            return ticksParRechargement;
        }

        public void setTicksParRechargement(int ticksParRechargement) {
            this.ticksParRechargement = ticksParRechargement;
        }

        public double getPartPerturbee() {
            return partPerturbee;
        }

        public void setPartPerturbee(double partPerturbee) {
            this.partPerturbee = partPerturbee;
        }
    }
}
