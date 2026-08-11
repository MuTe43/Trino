package tn.sncft.trino.iam.web;

import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tn.sncft.trino.commun.dto.PageDTO;
import tn.sncft.trino.iam.dto.UtilisateurCreateDTO;
import tn.sncft.trino.iam.dto.UtilisateurCreeDTO;
import tn.sncft.trino.iam.dto.UtilisateurDTO;
import tn.sncft.trino.iam.dto.UtilisateurUpdateDTO;
import tn.sncft.trino.iam.service.UtilisateurService;

/**
 * REST endpoints for user administration. Holds no business logic, only
 * maps requests to the service layer; the ADMINISTRATEUR-only restriction
 * lives on UtilisateurService as {@code @PreAuthorize}.
 */
@RestController
@RequestMapping("/api/v1/utilisateurs")
public class UtilisateurController {

    private final UtilisateurService utilisateurService;

    public UtilisateurController(UtilisateurService utilisateurService) {
        this.utilisateurService = utilisateurService;
    }

    @GetMapping
    public PageDTO<UtilisateurDTO> lister(@RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int taille) {
        Page<UtilisateurDTO> resultat = utilisateurService.lister(page, taille);
        return new PageDTO<>(resultat.getContent(), resultat.getNumber(), resultat.getSize(), resultat.getTotalElements());
    }

    @GetMapping("/{id}")
    public UtilisateurDTO trouverParId(@PathVariable Long id) {
        return utilisateurService.trouverParId(id);
    }

    @PostMapping
    public ResponseEntity<UtilisateurCreeDTO> creer(@Valid @RequestBody UtilisateurCreateDTO requete) {
        return ResponseEntity.status(HttpStatus.CREATED).body(utilisateurService.creer(requete));
    }

    @PatchMapping("/{id}")
    public UtilisateurDTO mettreAJour(@PathVariable Long id, @Valid @RequestBody UtilisateurUpdateDTO requete) {
        return utilisateurService.mettreAJour(id, requete);
    }

    @PostMapping("/{id}/mot-de-passe")
    public UtilisateurCreeDTO reinitialiserMotDePasse(@PathVariable Long id) {
        return utilisateurService.reinitialiserMotDePasse(id);
    }
}
