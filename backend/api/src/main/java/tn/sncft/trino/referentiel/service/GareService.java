package tn.sncft.trino.referentiel.service;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.sncft.trino.commun.PageableUtils;
import tn.sncft.trino.commun.RessourceIntrouvableException;
import tn.sncft.trino.referentiel.domaine.Gare;
import tn.sncft.trino.referentiel.dto.GareCreateDTO;
import tn.sncft.trino.referentiel.dto.GareDTO;
import tn.sncft.trino.referentiel.dto.GareUpdateDTO;
import tn.sncft.trino.referentiel.evenement.GareModifiee;
import tn.sncft.trino.referentiel.repo.GareRepository;

/**
 * Business logic for gares. Controllers hold no logic and only call here.
 */
@Service
@Transactional
public class GareService {

    private final GareRepository gareRepository;

    private final ApplicationEventPublisher publicateurEvenements;

    public GareService(GareRepository gareRepository, ApplicationEventPublisher publicateurEvenements) {
        this.gareRepository = gareRepository;
        this.publicateurEvenements = publicateurEvenements;
    }

    @Transactional(readOnly = true)
    public Page<GareDTO> lister(int page, int taille) {
        return gareRepository.findAll(PageableUtils.de(page, taille)).map(this::versDTO);
    }

    @Transactional(readOnly = true)
    public GareDTO trouverParId(Long id) {
        return versDTO(trouverEntiteParId(id));
    }

    @PreAuthorize("hasRole('ADMINISTRATEUR')")
    public GareDTO creer(GareCreateDTO requete) {
        Gare gare = new Gare();
        appliquer(gare, requete.code(), requete.nom(), requete.region(), requete.latitude(),
                requete.longitude(), requete.nbQuais(), requete.responsable(), requete.actif());
        return versDTO(gareRepository.save(gare));
    }

    @PreAuthorize("hasRole('ADMINISTRATEUR')")
    public GareDTO mettreAJour(Long id, GareUpdateDTO requete) {
        Gare gare = trouverEntiteParId(id);
        appliquer(gare, requete.code(), requete.nom(), requete.region(), requete.latitude(),
                requete.longitude(), requete.nbQuais(), requete.responsable(), requete.actif());
        GareDTO dto = versDTO(gareRepository.save(gare));
        // The coordinates may have moved, and every line geometry serving this
        // gare is anchored to them.
        publicateurEvenements.publishEvent(new GareModifiee(id));
        return dto;
    }

    @PreAuthorize("hasRole('ADMINISTRATEUR')")
    public void supprimer(Long id) {
        Gare gare = trouverEntiteParId(id);
        gareRepository.delete(gare);
        publicateurEvenements.publishEvent(new GareModifiee(id));
    }

    private Gare trouverEntiteParId(Long id) {
        return gareRepository.findById(id)
                .orElseThrow(() -> new RessourceIntrouvableException("Gare introuvable pour l'id " + id));
    }

    private void appliquer(Gare gare, String code, String nom, String region,
                            java.math.BigDecimal latitude, java.math.BigDecimal longitude,
                            Short nbQuais, String responsable, Boolean actif) {
        gare.setCode(code);
        gare.setNom(nom);
        gare.setRegion(region);
        gare.setLatitude(latitude);
        gare.setLongitude(longitude);
        gare.setNbQuais(nbQuais);
        gare.setResponsable(responsable);
        if (actif != null) {
            gare.setActif(actif);
        }
    }

    private GareDTO versDTO(Gare gare) {
        return new GareDTO(
                gare.getId(),
                gare.getCode(),
                gare.getNom(),
                gare.getRegion(),
                gare.getLatitude(),
                gare.getLongitude(),
                gare.getNbQuais(),
                gare.getResponsable(),
                gare.isActif()
        );
    }
}
