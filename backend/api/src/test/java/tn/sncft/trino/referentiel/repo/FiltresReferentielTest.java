package tn.sncft.trino.referentiel.repo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tn.sncft.trino.referentiel.domaine.Gare;
import tn.sncft.trino.referentiel.domaine.Train;
import tn.sncft.trino.referentiel.domaine.TypeTrain;
import tn.sncft.trino.support.BaseDeDonneesTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Specification-based filters for gares and trains, against the real
 * seed data -- so a {@code :param is null} regression (phase 6's
 * "could not determine data type of parameter $7") would be caught here
 * rather than only in the console.
 *
 * <p>Required, not optional, like every other DB-backed test in this phase --
 * see {@link BaseDeDonneesTest}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisabledIfSystemProperty(named = "trino.tests.sansDb", matches = "true",
        disabledReason = "opt-out explicite : base de données non requise")
class FiltresReferentielTest {

    @DynamicPropertySource
    static void source(DynamicPropertyRegistry registre) {
        registre.add("spring.datasource.url", () -> BaseDeDonneesTest.URL);
        registre.add("spring.datasource.username", () -> BaseDeDonneesTest.UTILISATEUR);
        registre.add("spring.datasource.password", () -> BaseDeDonneesTest.MOT_DE_PASSE);
    }

    @Autowired
    private GareRepository gareRepository;

    @Autowired
    private TrainRepository trainRepository;

    @Test
    @DisplayName("q=sous trouve au moins une gare du seed")
    void qTrouveUneGare() {
        Specification<Gare> specification = Specification.where(GareSpecifications.nomOuCodeContient("sous"));
        long total = gareRepository.findAll(specification, PageRequest.of(0, 200)).getTotalElements();

        assertTrue(total >= 1, "aucune gare ne matche q=sous dans le seed");
    }

    @Test
    @DisplayName("le filtre region restreint aux gares de cette région")
    void regionFiltre() {
        Specification<Gare> specification = Specification.where(GareSpecifications.regionEgale("Sousse"));
        var page = gareRepository.findAll(specification, PageRequest.of(0, 200));

        assertTrue(page.getTotalElements() >= 1, "aucune gare dans la région Sousse");
        assertTrue(page.getContent().stream().allMatch(g -> "Sousse".equalsIgnoreCase(g.getRegion())));
    }

    @Test
    @DisplayName("type=GRANDES_LIGNES trouve au moins un train du seed")
    void typeTrouveUnTrain() {
        Specification<Train> specification = Specification.where(TrainSpecifications.typeEgal(TypeTrain.GRANDES_LIGNES));
        var page = trainRepository.findAll(specification, PageRequest.of(0, 200));

        assertTrue(page.getTotalElements() >= 1, "aucun train GRANDES_LIGNES dans le seed");
        assertTrue(page.getContent().stream().allMatch(t -> t.getType() == TypeTrain.GRANDES_LIGNES));
    }

    @Test
    @DisplayName("le filtre ligneId restreint aux trains rattachés à cette ligne")
    void ligneIdFiltre() {
        Train unTrainAvecLigne = trainRepository.findAll().stream()
                .filter(t -> t.getLigne() != null)
                .findFirst().orElseThrow(() -> new IllegalStateException("aucun train rattaché à une ligne dans le seed"));
        Long ligneId = unTrainAvecLigne.getLigne().getId();

        Specification<Train> specification = Specification.where(TrainSpecifications.ligneIdEgal(ligneId));
        var page = trainRepository.findAll(specification, PageRequest.of(0, 200));

        assertTrue(page.getTotalElements() >= 1);
        assertTrue(page.getContent().stream()
                .allMatch(t -> t.getLigne() != null && ligneId.equals(t.getLigne().getId())));
    }

    @Test
    @DisplayName("tous les filtres à null rendent tout")
    void toutNullRendTout() {
        long totalGaresSansFiltre = gareRepository.count();
        Specification<Gare> specificationGare = Specification
                .where(GareSpecifications.regionEgale(null))
                .and(GareSpecifications.nomOuCodeContient(null));
        assertEquals(totalGaresSansFiltre, gareRepository.findAll(specificationGare, PageRequest.of(0, 500)).getTotalElements());

        long totalTrainsSansFiltre = trainRepository.count();
        Specification<Train> specificationTrain = Specification
                .where(TrainSpecifications.typeEgal(null))
                .and(TrainSpecifications.ligneIdEgal(null));
        assertEquals(totalTrainsSansFiltre, trainRepository.findAll(specificationTrain, PageRequest.of(0, 500)).getTotalElements());

        assertFalse(totalGaresSansFiltre == 0, "le seed doit fournir des gares");
        assertFalse(totalTrainsSansFiltre == 0, "le seed doit fournir des trains");
    }
}
