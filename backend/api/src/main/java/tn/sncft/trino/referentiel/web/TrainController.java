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
import tn.sncft.trino.referentiel.domaine.TypeTrain;
import tn.sncft.trino.referentiel.dto.TrainCreateDTO;
import tn.sncft.trino.referentiel.dto.TrainDTO;
import tn.sncft.trino.referentiel.dto.TrainUpdateDTO;
import tn.sncft.trino.referentiel.service.TrainService;

/**
 * REST endpoints for trains (rolling stock). Holds no business logic, only
 * maps requests to the service layer.
 */
@RestController
@RequestMapping("/api/v1/trains")
public class TrainController {

    private final TrainService trainService;

    public TrainController(TrainService trainService) {
        this.trainService = trainService;
    }

    @GetMapping
    public PageDTO<TrainDTO> lister(@RequestParam(required = false) TypeTrain type,
                                     @RequestParam(required = false) Long ligneId,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "20") int taille) {
        Page<TrainDTO> resultat = trainService.lister(type, ligneId, page, taille);
        return new PageDTO<>(resultat.getContent(), resultat.getNumber(), resultat.getSize(), resultat.getTotalElements());
    }

    @GetMapping("/{id}")
    public TrainDTO trouverParId(@PathVariable Long id) {
        return trainService.trouverParId(id);
    }

    @PostMapping
    public ResponseEntity<TrainDTO> creer(@Valid @RequestBody TrainCreateDTO requete) {
        return ResponseEntity.status(HttpStatus.CREATED).body(trainService.creer(requete));
    }

    @PutMapping("/{id}")
    public TrainDTO mettreAJour(@PathVariable Long id, @Valid @RequestBody TrainUpdateDTO requete) {
        return trainService.mettreAJour(id, requete);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> supprimer(@PathVariable Long id) {
        trainService.supprimer(id);
        return ResponseEntity.noContent().build();
    }
}
