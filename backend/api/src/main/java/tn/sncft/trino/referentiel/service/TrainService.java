package tn.sncft.trino.referentiel.service;

import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.sncft.trino.commun.PageableUtils;
import tn.sncft.trino.commun.RessourceIntrouvableException;
import tn.sncft.trino.referentiel.domaine.Ligne;
import tn.sncft.trino.referentiel.domaine.Train;
import tn.sncft.trino.referentiel.domaine.TypeTrain;
import tn.sncft.trino.referentiel.dto.TrainCreateDTO;
import tn.sncft.trino.referentiel.dto.TrainDTO;
import tn.sncft.trino.referentiel.dto.TrainUpdateDTO;
import tn.sncft.trino.referentiel.repo.LigneRepository;
import tn.sncft.trino.referentiel.repo.TrainRepository;

/**
 * Business logic for trains (rolling stock). No status, no delay: those
 * belong on Course, which does not exist in this phase.
 */
@Service
@Transactional
public class TrainService {

    private final TrainRepository trainRepository;
    private final LigneRepository ligneRepository;

    public TrainService(TrainRepository trainRepository, LigneRepository ligneRepository) {
        this.trainRepository = trainRepository;
        this.ligneRepository = ligneRepository;
    }

    @Transactional(readOnly = true)
    public Page<TrainDTO> lister(int page, int taille) {
        return trainRepository.findAll(PageableUtils.de(page, taille)).map(this::versDTO);
    }

    @Transactional(readOnly = true)
    public TrainDTO trouverParId(Long id) {
        return versDTO(trouverEntiteParId(id));
    }

    @PreAuthorize("hasRole('ADMINISTRATEUR')")
    public TrainDTO creer(TrainCreateDTO requete) {
        Train train = new Train();
        appliquer(train, requete.numero(), requete.nom(), requete.type(), requete.ligneId(),
                requete.capacite(), requete.vitesseMaxKmh(), requete.actif());
        return versDTO(trainRepository.save(train));
    }

    @PreAuthorize("hasRole('ADMINISTRATEUR')")
    public TrainDTO mettreAJour(Long id, TrainUpdateDTO requete) {
        Train train = trouverEntiteParId(id);
        appliquer(train, requete.numero(), requete.nom(), requete.type(), requete.ligneId(),
                requete.capacite(), requete.vitesseMaxKmh(), requete.actif());
        return versDTO(trainRepository.save(train));
    }

    @PreAuthorize("hasRole('ADMINISTRATEUR')")
    public void supprimer(Long id) {
        Train train = trouverEntiteParId(id);
        trainRepository.delete(train);
    }

    private Train trouverEntiteParId(Long id) {
        return trainRepository.findById(id)
                .orElseThrow(() -> new RessourceIntrouvableException("Train introuvable pour l'id " + id));
    }

    private void appliquer(Train train, String numero, String nom, TypeTrain type, Long ligneId,
                            Short capacite, Short vitesseMaxKmh, Boolean actif) {
        train.setNumero(numero);
        train.setNom(nom);
        train.setType(type);
        train.setLigne(resoudreLigne(ligneId));
        train.setCapacite(capacite);
        train.setVitesseMaxKmh(vitesseMaxKmh);
        if (actif != null) {
            train.setActif(actif);
        }
    }

    private Ligne resoudreLigne(Long ligneId) {
        if (ligneId == null) {
            return null;
        }
        return ligneRepository.findById(ligneId)
                .orElseThrow(() -> new RessourceIntrouvableException("Ligne introuvable pour l'id " + ligneId));
    }

    private TrainDTO versDTO(Train train) {
        return new TrainDTO(
                train.getId(),
                train.getNumero(),
                train.getNom(),
                train.getType(),
                train.getLigne() != null ? train.getLigne().getId() : null,
                train.getCapacite(),
                train.getVitesseMaxKmh(),
                train.isActif()
        );
    }
}
