package tn.sncft.trino.diffusion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The masking of the {@code abonne:} channel name on the way out.
 *
 * <p>Worth a test of its own because getting it wrong is a credential leak with
 * no symptom. The real channel name embeds the subscriber's token, which reaches
 * the browser as an {@code HttpOnly} cookie precisely so that scripts on the
 * page cannot read it -- and a frame tagged with the real name would hand it
 * back on every notification, in plain sight of anything listening on the
 * EventSource. Nothing would break; the token would simply be readable.
 *
 * <p>In the same package as {@link AbonnementSse}, which is package-private on
 * purpose: the hub stores it and nobody else needs to.
 */
class AbonnementSseTest {

    private static final String JETON = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    @Test
    @DisplayName("un canal abonné est étiqueté par son alias, jamais par le jeton")
    void canalAbonneEstMasque() {
        String canal = HubSse.canalAbonneJeton(JETON);
        AbonnementSse abonnement = new AbonnementSse(new SseEmitter(), Set.of(canal), true);

        Object charge = abonnement.charge(List.of(canal), "{}");

        assertThat(charge).isInstanceOf(EnveloppeSse.class);
        EnveloppeSse enveloppe = (EnveloppeSse) charge;
        assertThat(enveloppe.canaux()).containsExactly(HubSse.CANAL_ABONNE_ALIAS);
        assertThat(enveloppe.canaux()).noneMatch(nom -> nom.contains(JETON));
    }

    @Test
    @DisplayName("un canal de compte est masqué de la même façon")
    void canalDeCompteEstMasque() {
        String canal = HubSse.canalAbonneCompte(7L);
        AbonnementSse abonnement = new AbonnementSse(new SseEmitter(), Set.of(canal), true);

        EnveloppeSse enveloppe = (EnveloppeSse) abonnement.charge(List.of(canal), "{}");

        assertThat(enveloppe.canaux()).containsExactly(HubSse.CANAL_ABONNE_ALIAS);
    }

    /** Ligne and gare channels are public ids and stay exactly as they are. */
    @Test
    @DisplayName("les canaux ligne et gare ne sont pas masqués")
    void canauxPublicsInchanges() {
        Set<String> canaux = Set.of(HubSse.canalLigne(1L), HubSse.canalGare(7L));
        AbonnementSse abonnement = new AbonnementSse(new SseEmitter(), canaux, true);

        EnveloppeSse enveloppe = (EnveloppeSse) abonnement.charge(
                List.of("ligne:1", "gare:7"), "{}");

        assertThat(enveloppe.canaux()).containsExactlyInAnyOrder("ligne:1", "gare:7");
    }

    /**
     * The single-channel endpoints (the kiosk board) keep the bare payload
     * documented in api-contract.md -- no envelope, and so nothing to mask.
     */
    @Test
    @DisplayName("un abonnement mono-canal reçoit la charge nue")
    void monoCanalRecoitLaChargeNue() {
        AbonnementSse abonnement = new AbonnementSse(new SseEmitter(), Set.of("gare:7"), false);

        assertThat(abonnement.charge(List.of("gare:7"), "{}")).isEqualTo("{}");
    }

    /** The account and token spaces cannot collide: a token "12" is not account 12. */
    @Test
    @DisplayName("les espaces jeton et compte sont disjoints")
    void espacesDisjoints() {
        assertThat(HubSse.canalAbonneJeton("12")).isNotEqualTo(HubSse.canalAbonneCompte(12L));
    }
}
