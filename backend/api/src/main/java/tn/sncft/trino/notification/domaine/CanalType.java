package tn.sncft.trino.notification.domaine;

/**
 * How a notification reaches someone.
 *
 * <p>Two of these genuinely work and two do not, which is decision 7 as revised
 * at phase 8: {@code IN_APP} rides the existing SSE hub, {@code EMAIL} goes over
 * real SMTP to Mailpit. {@code SMS} is a Twilio-shaped stub that logs, and
 * {@code AFFICHAGE} is not a new transport at all -- the station board already
 * consumes {@code gare:{id}}, so the engine records the notification and emits
 * nothing new.
 *
 * <p>Four half-working integrations would be worse than this. Zero working ones
 * was worse than either.
 */
public enum CanalType {
    IN_APP,
    EMAIL,
    SMS,
    AFFICHAGE
}
