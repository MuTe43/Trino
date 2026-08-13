package tn.sncft.trino.notification.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tn.sncft.trino.circulation.service.HorlogeCirculation;
import tn.sncft.trino.notification.domaine.Evenement;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The guard that keeps an x20 replay from sending a subscriber hundreds of
 * messages a minute.
 *
 * <p>The clock is stubbed rather than driven for real: the window is 30
 * simulated minutes, and a test that waited them out would take 30 minutes.
 */
class DedoublonneurTest {

    private static final OffsetDateTime DEPART = OffsetDateTime.parse("2026-08-12T06:00:00Z");

    private HorlogeCirculation horloge;
    private Dedoublonneur dedoublonneur;

    @BeforeEach
    void preparer() {
        horloge = mock(HorlogeCirculation.class);
        dedoublonneur = new Dedoublonneur(horloge);
    }

    private void a(String duree) {
        when(horloge.maintenant()).thenReturn(DEPART.plusMinutes(Long.parseLong(duree)));
    }

    @Test
    @DisplayName("le premier passage est autorisé, les suivants dans la fenêtre sont refusés")
    void premierAutoriseSuivantsRefuses() {
        a("0");
        assertThat(dedoublonneur.autoriser(1L, Evenement.RETARD_SEUIL, 42L)).isTrue();

        // Every ping of the same course while it stays late. Without the guard
        // each of these is a notification.
        for (int minute = 1; minute <= 29; minute++) {
            a(String.valueOf(minute));
            assertThat(dedoublonneur.autoriser(1L, Evenement.RETARD_SEUIL, 42L)).isFalse();
        }
    }

    @Test
    @DisplayName("passé la fenêtre de 30 minutes simulées, une nouvelle notification part")
    void nouvelleFenetreApres30Minutes() {
        a("0");
        assertThat(dedoublonneur.autoriser(1L, Evenement.RETARD_SEUIL, 42L)).isTrue();

        a("30");
        // Strictly after: at exactly 30 the window has not elapsed yet.
        assertThat(dedoublonneur.autoriser(1L, Evenement.RETARD_SEUIL, 42L)).isFalse();

        a("31");
        assertThat(dedoublonneur.autoriser(1L, Evenement.RETARD_SEUIL, 42L)).isTrue();
    }

    /**
     * The key is (subscription, event, course) -- all three parts. A guard keyed
     * on the course alone would silence every other subscriber of a late train
     * after the first one was notified.
     */
    @Test
    @DisplayName("la clé porte sur l'abonnement, l'événement et la course")
    void laCleEstComposee() {
        a("0");
        assertThat(dedoublonneur.autoriser(1L, Evenement.RETARD_SEUIL, 42L)).isTrue();

        assertThat(dedoublonneur.autoriser(2L, Evenement.RETARD_SEUIL, 42L)).isTrue();
        assertThat(dedoublonneur.autoriser(1L, Evenement.COURSE_ANNULEE, 42L)).isTrue();
        assertThat(dedoublonneur.autoriser(1L, Evenement.RETARD_SEUIL, 43L)).isTrue();

        assertThat(dedoublonneur.autoriser(1L, Evenement.RETARD_SEUIL, 42L)).isFalse();
    }

    /** A ligne-wide incident has no course; null is part of the key, not an error. */
    @Test
    @DisplayName("une course nulle est une clé valide")
    void courseNulleEstUneCle() {
        a("0");
        assertThat(dedoublonneur.autoriser(1L, Evenement.INCIDENT_DECLARE, null)).isTrue();
        assertThat(dedoublonneur.autoriser(1L, Evenement.INCIDENT_DECLARE, null)).isFalse();
    }

    /**
     * Without eviction the map holds a key per (subscriber, course) for the life
     * of the process -- and a replay walks through every course of the day.
     */
    @Test
    @DisplayName("les fenêtres périmées sont évincées")
    void fenetresPerimeesEvincees() {
        a("0");
        for (long course = 1; course <= 50; course++) {
            dedoublonneur.autoriser(1L, Evenement.RETARD_SEUIL, course);
        }
        assertThat(dedoublonneur.taille()).isEqualTo(50);

        // One emission well past the window sweeps everything older than it.
        a("120");
        dedoublonneur.autoriser(1L, Evenement.RETARD_SEUIL, 999L);
        assertThat(dedoublonneur.taille()).isEqualTo(1);
    }
}
