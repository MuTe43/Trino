package tn.sncft.trino.circulation.domaine;

/**
 * Why a course is late. Set by the delay engine in phase 3, or by an agent.
 */
public enum CauseRetard {
    INCIDENT_TECHNIQUE,
    METEO,
    ACCIDENT,
    SIGNALISATION,
    TRAVAUX,
    ATTENTE_CORRESPONDANCE,
    AFFLUENCE_VOYAGEURS,
    AUTRE
}
