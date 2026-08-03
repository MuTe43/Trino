package tn.sncft.trino.referentiel.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.sncft.trino.commun.PageableUtils;
import tn.sncft.trino.commun.RessourceIntrouvableException;
import tn.sncft.trino.referentiel.domaine.Desserte;
import tn.sncft.trino.referentiel.domaine.Ligne;
import tn.sncft.trino.referentiel.dto.DesserteDTO;
import tn.sncft.trino.referentiel.dto.LigneCreateDTO;
import tn.sncft.trino.referentiel.dto.LigneDTO;
import tn.sncft.trino.referentiel.dto.LigneUpdateDTO;
import tn.sncft.trino.referentiel.repo.DesserteRepository;
import tn.sncft.trino.referentiel.repo.LigneRepository;

import java.math.BigDecimal;
import java.util.List;

/**
 * Business logic for lignes. Owns the JSON string <-> List<List<Double>>
 * conversion for the `trace` polyline; this parsing never happens in the
 * entity or the controller.
 */
@Service
@Transactional
public class LigneService {

    private final LigneRepository ligneRepository;
    private final DesserteRepository desserteRepository;
    private final ObjectMapper objectMapper;

    public LigneService(LigneRepository ligneRepository, DesserteRepository desserteRepository,
                         ObjectMapper objectMapper) {
        this.ligneRepository = ligneRepository;
        this.desserteRepository = desserteRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public Page<LigneDTO> lister(int page, int taille) {
        return ligneRepository.findAll(PageableUtils.de(page, taille)).map(this::versDTO);
    }

    @Transactional(readOnly = true)
    public LigneDTO trouverParId(Long id) {
        return versDTO(trouverEntiteParId(id));
    }

    @Transactional(readOnly = true)
    public List<DesserteDTO> trouverDesserte(Long ligneId) {
        if (!ligneRepository.existsById(ligneId)) {
            throw new RessourceIntrouvableException("Ligne introuvable pour l'id " + ligneId);
        }
        return desserteRepository.findByLigneIdOrderByOrdreAsc(ligneId).stream()
                .map(this::desserteVersDTO)
                .toList();
    }

    @PreAuthorize("hasRole('ADMINISTRATEUR')")
    public LigneDTO creer(LigneCreateDTO requete) {
        Ligne ligne = new Ligne();
        appliquer(ligne, requete.code(), requete.nom(), requete.distanceKm(), requete.vitesseMaxKmh(),
                requete.tempsTheoriqueMin(), requete.trace(), requete.actif());
        return versDTO(ligneRepository.save(ligne));
    }

    @PreAuthorize("hasRole('ADMINISTRATEUR')")
    public LigneDTO mettreAJour(Long id, LigneUpdateDTO requete) {
        Ligne ligne = trouverEntiteParId(id);
        appliquer(ligne, requete.code(), requete.nom(), requete.distanceKm(), requete.vitesseMaxKmh(),
                requete.tempsTheoriqueMin(), requete.trace(), requete.actif());
        return versDTO(ligneRepository.save(ligne));
    }

    @PreAuthorize("hasRole('ADMINISTRATEUR')")
    public void supprimer(Long id) {
        Ligne ligne = trouverEntiteParId(id);
        ligneRepository.delete(ligne);
    }

    private Ligne trouverEntiteParId(Long id) {
        return ligneRepository.findById(id)
                .orElseThrow(() -> new RessourceIntrouvableException("Ligne introuvable pour l'id " + id));
    }

    private void appliquer(Ligne ligne, String code, String nom, BigDecimal distanceKm, Short vitesseMaxKmh,
                            Short tempsTheoriqueMin, List<List<Double>> trace, Boolean actif) {
        ligne.setCode(code);
        ligne.setNom(nom);
        ligne.setDistanceKm(distanceKm);
        ligne.setVitesseMaxKmh(vitesseMaxKmh);
        ligne.setTempsTheoriqueMin(tempsTheoriqueMin);
        ligne.setTrace(serialiserTrace(trace));
        if (actif != null) {
            ligne.setActif(actif);
        }
    }

    private String serialiserTrace(List<List<Double>> trace) {
        if (trace == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(trace);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Trace invalide", e);
        }
    }

    private List<List<Double>> deserialiserTrace(String trace) {
        if (trace == null || trace.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(trace, new TypeReference<List<List<Double>>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Trace stockée invalide", e);
        }
    }

    private LigneDTO versDTO(Ligne ligne) {
        return new LigneDTO(
                ligne.getId(),
                ligne.getCode(),
                ligne.getNom(),
                ligne.getDistanceKm(),
                ligne.getVitesseMaxKmh(),
                ligne.getTempsTheoriqueMin(),
                deserialiserTrace(ligne.getTrace()),
                ligne.isActif()
        );
    }

    private DesserteDTO desserteVersDTO(Desserte desserte) {
        return new DesserteDTO(
                desserte.getId(),
                desserte.getLigne().getId(),
                desserte.getGare().getId(),
                desserte.getGare().getNom(),
                desserte.getOrdre(),
                desserte.getPkKm(),
                desserte.getOffsetArriveeMin(),
                desserte.getOffsetDepartMin()
        );
    }
}
