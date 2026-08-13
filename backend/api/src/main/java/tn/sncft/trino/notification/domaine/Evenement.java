package tn.sncft.trino.notification.domaine;

/**
 * What a {@link RegleAlerte} reacts to.
 *
 * <p>These are notification-side names for deltas the system already publishes;
 * they are not a second event stream. {@code RETARD_SEUIL} and
 * {@code COURSE_ANNULEE} come from what {@code DiffuseurCirculation} emits,
 * {@code INCIDENT_DECLARE} and {@code INCIDENT_RESOLU} from what
 * {@code DiffuseurIncident} emits.
 */
public enum Evenement {
    RETARD_SEUIL,
    COURSE_ANNULEE,
    INCIDENT_DECLARE,
    INCIDENT_RESOLU
}
