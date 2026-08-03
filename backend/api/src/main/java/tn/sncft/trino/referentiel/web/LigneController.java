package tn.sncft.trino.referentiel.web;

import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tn.sncft.trino.commun.dto.PageDTO;
import tn.sncft.trino.referentiel.dto.DesserteDTO;
import tn.sncft.trino.referentiel.dto.LigneCreateDTO;
import tn.sncft.trino.referentiel.dto.LigneDTO;
import tn.sncft.trino.referentiel.dto.LigneUpdateDTO;
import tn.sncft.trino.referentiel.service.LigneService;

import java.util.List;

/**
 * REST endpoints for lignes. Holds no business logic, only maps requests
 * to the service layer.
 */
@RestController
@RequestMapping("/api/v1/lignes")
public class LigneController {

    private final LigneService ligneService;

    public LigneController(LigneService ligneService) {
        this.ligneService = ligneService;
    }

    @GetMapping
    public PageDTO<LigneDTO> lister(@RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "20") int taille) {
        Page<LigneDTO> resultat = ligneService.lister(page, taille);
        return new PageDTO<>(resultat.getContent(), resultat.getNumber(), resultat.getSize(), resultat.getTotalElements());
    }

    @GetMapping("/{id}")
    public LigneDTO trouverParId(@PathVariable Long id) {
        return ligneService.trouverParId(id);
    }

    @GetMapping("/{id}/desserte")
    public List<DesserteDTO> trouverDesserte(@PathVariable Long id) {
        return ligneService.trouverDesserte(id);
    }

    @PostMapping
    public ResponseEntity<LigneDTO> creer(@Valid @RequestBody LigneCreateDTO requete) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ligneService.creer(requete));
    }

    @PutMapping("/{id}")
    public LigneDTO mettreAJour(@PathVariable Long id, @Valid @RequestBody LigneUpdateDTO requete) {
        return ligneService.mettreAJour(id, requete);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> supprimer(@PathVariable Long id) {
        ligneService.supprimer(id);
        return ResponseEntity.noContent().build();
    }
}
