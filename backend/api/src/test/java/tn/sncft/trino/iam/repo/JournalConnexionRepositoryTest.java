package tn.sncft.trino.iam.repo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tn.sncft.trino.iam.domaine.JournalConnexion;
import tn.sncft.trino.iam.domaine.Utilisateur;
import tn.sncft.trino.support.BaseDeDonneesTest;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Specification-based filters backing {@code JournalService.consulter},
 * against the real database -- every combination, including all-null. This
 * is the class that would have caught the phase-6 defect: {@code :param is
 * null} in a {@code @Query} makes PostgreSQL answer "could not determine data
 * type of parameter $7" the moment every filter is absent, exactly the
 * console's default view.
 *
 * <p>Seeds its own rows, marked by a distinctive {@code adresseIp} so
 * assertions hold whatever the dev database already contains -- the whole
 * test runs in a transaction {@code @DataJpaTest} rolls back.
 *
 * <p>Required, not optional -- see {@link BaseDeDonneesTest}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisabledIfSystemProperty(named = "trino.tests.sansDb", matches = "true",
        disabledReason = "opt-out explicite : base de données non requise")
class JournalConnexionRepositoryTest {

    /** TEST-NET-3 (RFC 5737): never a real caller's address. */
    private static final String MARQUEUR_IP = "203.0.113.1";

    private static final OffsetDateTime JOUR = OffsetDateTime.parse("2020-01-15T09:00:00Z");

    @DynamicPropertySource
    static void source(DynamicPropertyRegistry registre) {
        registre.add("spring.datasource.url", () -> BaseDeDonneesTest.URL);
        registre.add("spring.datasource.username", () -> BaseDeDonneesTest.UTILISATEUR);
        registre.add("spring.datasource.password", () -> BaseDeDonneesTest.MOT_DE_PASSE);
    }

    @Autowired
    private JournalConnexionRepository journalConnexionRepository;

    @Autowired
    private TestEntityManager entityManager;

    private Utilisateur utilisateur;
    private Utilisateur autreUtilisateur;

    @BeforeEach
    void preparer() {
        var utilisateurs = entityManager.getEntityManager()
                .createQuery("select u from Utilisateur u order by u.id", Utilisateur.class)
                .setMaxResults(2).getResultList();
        assertTrue(utilisateurs.size() >= 2, "le seed doit fournir au moins deux comptes");
        utilisateur = utilisateurs.get(0);
        autreUtilisateur = utilisateurs.get(1);
    }

    private JournalConnexion seed(Long utilisateurId, boolean succes, OffsetDateTime horodatage) {
        JournalConnexion journal = new JournalConnexion();
        journal.setUtilisateurId(utilisateurId);
        journal.setEmailTente(utilisateurId != null ? utilisateur.getEmail() : "inconnu@sncft.tn");
        journal.setSucces(succes);
        journal.setAdresseIp(MARQUEUR_IP);
        journal.setUserAgent("JournalConnexionRepositoryTest");
        journal.setHorodatage(horodatage);
        return entityManager.persistAndFlush(journal);
    }

    private Page<JournalConnexion> rechercher(Boolean succes, Long utilisateurId,
                                              OffsetDateTime debut, OffsetDateTime finExclusive) {
        Specification<JournalConnexion> specification = Specification
                .where(JournalConnexionSpecifications.succesEgal(succes))
                .and(JournalConnexionSpecifications.utilisateurIdEgal(utilisateurId))
                .and(JournalConnexionSpecifications.horodatageDepuis(debut))
                .and(JournalConnexionSpecifications.horodatageAvant(finExclusive));
        return journalConnexionRepository.findAll(specification, PageRequest.of(0, 200));
    }

    /** Counts only the rows this test seeded, whatever else the database holds. */
    private long compter(Boolean succes, Long utilisateurId, OffsetDateTime debut, OffsetDateTime finExclusive) {
        return rechercher(succes, utilisateurId, debut, finExclusive).getContent().stream()
                .filter(j -> MARQUEUR_IP.equals(j.getAdresseIp()))
                .count();
    }

    @Test
    @DisplayName("tous les filtres à null exécutent sans lever d'exception de typage")
    void tousLesFiltresANullExecutent() {
        seed(utilisateur.getId(), true, JOUR);

        // The phase-6 defect surfaced exactly here: PostgreSQL cannot type an
        // untyped bind parameter in a ":param is null" JPQL clause, and this
        // is the console's default view -- every filter absent.
        assertTrue(compter(null, null, null, null) >= 1);
    }

    @Test
    @DisplayName("le filtre succes distingue les tentatives réussies des échouées")
    void succesFiltre() {
        seed(utilisateur.getId(), true, JOUR);
        seed(null, false, JOUR.plusMinutes(1));

        assertEquals(1, compter(true, null, null, null));
        assertEquals(1, compter(false, null, null, null));
    }

    @Test
    @DisplayName("le filtre utilisateurId restreint aux tentatives de ce compte")
    void utilisateurIdFiltre() {
        seed(utilisateur.getId(), true, JOUR);
        seed(autreUtilisateur.getId(), true, JOUR.plusMinutes(1));

        assertEquals(1, compter(null, utilisateur.getId(), null, null));
        assertEquals(1, compter(null, autreUtilisateur.getId(), null, null));
    }

    @Test
    @DisplayName("un id inconnu (tentative sur un email qui n'existe pas) n'est jamais compté par le filtre utilisateurId")
    void idNulJamaisMatchePourUnEmailInconnu() {
        seed(null, false, JOUR);

        assertEquals(0, compter(null, utilisateur.getId(), null, null));
    }

    @Test
    @DisplayName("la plage horodatage borne des deux côtés, y compris dans le sens qui a cassé en phase 6")
    void plageHorodatageBorneLesDeuxCotes() {
        seed(utilisateur.getId(), true, JOUR);

        assertEquals(1, compter(null, null, JOUR.minusDays(1), JOUR.plusDays(1)));
        assertEquals(0, compter(null, null, JOUR.plusDays(1), null));
        assertEquals(0, compter(null, null, null, JOUR.minusDays(1)));
    }

    @Test
    @DisplayName("les filtres se combinent")
    void lesFiltresSeCombinent() {
        seed(utilisateur.getId(), true, JOUR);
        seed(utilisateur.getId(), false, JOUR.plusMinutes(1));
        seed(autreUtilisateur.getId(), true, JOUR.plusMinutes(2));

        assertEquals(1, compter(true, utilisateur.getId(), JOUR.minusDays(1), JOUR.plusDays(1)));
    }
}
