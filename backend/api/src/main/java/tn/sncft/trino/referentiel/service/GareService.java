package tn.sncft.trino.referentiel.service;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.sncft.trino.commun.ConflitException;
import tn.sncft.trino.commun.PageableUtils;
import tn.sncft.trino.commun.RessourceIntrouvableException;
import tn.sncft.trino.referentiel.domaine.Gare;
import tn.sncft.trino.referentiel.dto.GareCreateDTO;
import tn.sncft.trino.referentiel.dto.GareDTO;
import tn.sncft.trino.referentiel.dto.GareUpdateDTO;
import tn.sncft.trino.referentiel.evenement.GareModifiee;
import tn.sncft.trino.referentiel.repo.GareRepository;
import tn.sncft.trino.referentiel.repo.GareSpecifications;

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
    public Page<GareDTO> lister(String region, String q, int page, int taille) {
        String regionFiltre = blancVersNull(region);
        String qFiltre = blancVersNull(q);
        Specification<Gare> specification = Specification
                .where(GareSpecifications.regionEgale(regionFiltre))
                .and(GareSpecifications.nomOuCodeContient(qFiltre));
        return gareRepository.findAll(specification, PageableUtils.de(page, taille)).map(this::versDTO);
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
        try {
            gareRepository.delete(gare);
            // Forces the FK check now, inside the try -- without it the
            // violation only surfaces at commit, outside this catch.
            gareRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new ConflitException(
                    "Impossible de supprimer la gare " + id
                            + " : elle est référencée par une desserte, des courses ou des trains.");
        }
        publicateurEvenements.publishEvent(new GareModifiee(id));
    }

    private Gare trouverEntiteParId(Long id) {
        return gareRepository.findById(id)
                .orElseThrow(() -> new RessourceIntrouvableException("Gare introuvable pour l'id " + id));
    }

    private String blancVersNull(String valeur) {
        return valeur == null || valeur.isBlank() ? null : valeur;
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
