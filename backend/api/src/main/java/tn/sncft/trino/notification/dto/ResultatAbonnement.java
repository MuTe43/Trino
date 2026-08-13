package tn.sncft.trino.notification.dto;

/**
 * What {@code ServiceAbonnement.enregistrer} returns: the subscription, and
 * whether this call created it.
 *
 * <p>In {@code dto/} rather than nested in the service, because it crosses the
 * controller boundary — invariant 7 puts every such record here. The boolean is
 * the whole reason it exists: re-subscribing is not an error, so the controller
 * answers 201 on a new row and 200 on an updated one, and it must not have to
 * re-derive which happened.
 */
public record ResultatAbonnement(AbonnementDTO abonnement, boolean cree) {
}
