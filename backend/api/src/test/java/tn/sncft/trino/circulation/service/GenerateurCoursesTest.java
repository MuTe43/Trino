package tn.sncft.trino.circulation.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tn.sncft.trino.referentiel.domaine.Gare;
import tn.sncft.trino.referentiel.domaine.Train;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link GenerateurCourses#quaiPour}, the deterministic
 * platform assignment behind phase 4 job 2.
 */
class GenerateurCoursesTest {

    @Test
    @DisplayName("le même train à la même gare obtient toujours le même quai")
    void memeTrainMemeGareMemeQuai() {
        Train train = train(101L);
        Gare gare = gare(5L, (short) 3);

        String premier = GenerateurCourses.quaiPour(train, gare);
        String second = GenerateurCourses.quaiPour(train, gare);

        assertEquals(premier, second);
    }

    @Test
    @DisplayName("une gare à un seul quai reçoit toujours \"1\"")
    void nbQuaisUnDonneToujoursQuaiUn() {
        Gare gare = gare(5L, (short) 1);

        assertEquals("1", GenerateurCourses.quaiPour(train(1L), gare));
        assertEquals("1", GenerateurCourses.quaiPour(train(999L), gare));
    }

    @Test
    @DisplayName("une gare sans nb_quais ne se voit inventer aucun quai")
    void nbQuaisNullNeProduitPasDeQuai() {
        Gare gare = gare(5L, null);

        assertNull(GenerateurCourses.quaiPour(train(1L), gare));
    }

    @Test
    @DisplayName("une gare avec nb_quais à zéro ne se voit inventer aucun quai")
    void nbQuaisZeroNeProduitPasDeQuai() {
        Gare gare = gare(5L, (short) 0);

        assertNull(GenerateurCourses.quaiPour(train(1L), gare));
    }

    @Test
    @DisplayName("le quai assigné reste toujours dans les bornes de nb_quais")
    void quaiResteDansLesBornes() {
        Gare gare = gare(7L, (short) 4);

        for (long trainId = 1; trainId <= 50; trainId++) {
            String quai = GenerateurCourses.quaiPour(train(trainId), gare);
            int valeur = Integer.parseInt(quai);
            assertTrue(valeur >= 1 && valeur <= 4, "quai " + quai + " hors bornes pour nb_quais=4");
        }
    }

    private static Train train(Long id) {
        Train train = new Train();
        train.setId(id);
        return train;
    }

    private static Gare gare(Long id, Short nbQuais) {
        Gare gare = new Gare();
        gare.setId(id);
        gare.setNbQuais(nbQuais);
        return gare;
    }
}
