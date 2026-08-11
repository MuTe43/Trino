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
import tn.sncft.trino.referentiel.dto.GareCreateDTO;
import tn.sncft.trino.referentiel.dto.GareDTO;
import tn.sncft.trino.referentiel.dto.GareUpdateDTO;
import tn.sncft.trino.referentiel.service.GareService;

/**
 * REST endpoints for gares. Holds no business logic, only maps requests
 * to the service layer.
 */
@RestController
@RequestMapping("/api/v1/gares")
public class GareController {

    private final GareService gareService;

    public GareController(GareService gareService) {
        this.gareService = gareService;
    }

    @GetMapping
    public PageDTO<GareDTO> lister(@RequestParam(required = false) String region,
                                    @RequestParam(required = false) String q,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int taille) {
        Page<GareDTO> resultat = gareService.lister(region, q, page, taille);
        return new PageDTO<>(resultat.getContent(), resultat.getNumber(), resultat.getSize(), resultat.getTotalElements());
    }

    @GetMapping("/{id}")
    public GareDTO trouverParId(@PathVariable Long id) {
        return gareService.trouverParId(id);
    }

    @PostMapping
    public ResponseEntity<GareDTO> creer(@Valid @RequestBody GareCreateDTO requete) {
        return ResponseEntity.status(HttpStatus.CREATED).body(gareService.creer(requete));
    }

    @PutMapping("/{id}")
    public GareDTO mettreAJour(@PathVariable Long id, @Valid @RequestBody GareUpdateDTO requete) {
        return gareService.mettreAJour(id, requete);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> supprimer(@PathVariable Long id) {
        gareService.supprimer(id);
        return ResponseEntity.noContent().build();
    }
}
