package tn.sncft.trino.securite;

/**
 * Who a public notification request belongs to: an account, or an anonymous
 * token, never both and never neither.
 *
 * <p>This mirrors {@code chk_abonnement_identite} at the request boundary. Every
 * one of {@code POST /abonnements}, {@code DELETE /abonnements/{id}},
 * {@code GET /abonnements/miennes}, {@code GET /notifications} and the
 * {@code abonne:} SSE channel resolves the caller through this one type, so the
 * rule for "whose subscriptions are these" is written once rather than four
 * times with three of them subtly different.
 *
 * @param utilisateurId the account, when the request is authenticated
 * @param jeton         the anonymous bearer token, otherwise
 */
public record IdentiteAbonne(Long utilisateurId, String jeton) {

    public static IdentiteAbonne deCompte(Long utilisateurId) {
        return new IdentiteAbonne(utilisateurId, null);
    }

    public static IdentiteAbonne deJeton(String jeton) {
        return new IdentiteAbonne(null, jeton);
    }

    public boolean estAnonyme() {
        return jeton != null;
    }
}
