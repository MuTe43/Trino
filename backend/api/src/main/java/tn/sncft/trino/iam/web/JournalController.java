package tn.sncft.trino.iam.web;

import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tn.sncft.trino.commun.dto.PageDTO;
import tn.sncft.trino.iam.dto.JournalConnexionDTO;
import tn.sncft.trino.iam.service.JournalService;

import java.time.LocalDate;

/**
 * REST endpoint for the login audit trail. Holds no business logic, only
 * maps requests to the service layer; the ADMINISTRATEUR-only restriction
 * lives on JournalService as {@code @PreAuthorize}.
 */
@RestController
@RequestMapping("/api/v1/journal-connexions")
public class JournalController {

    private final JournalService journalService;

    public JournalController(JournalService journalService) {
        this.journalService = journalService;
    }

    @GetMapping
    public PageDTO<JournalConnexionDTO> consulter(@RequestParam(required = false) Boolean succes,
                                                    @RequestParam(required = false) Long utilisateurId,
                                                    @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate du,
                                                    @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate au,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int taille) {
        Page<JournalConnexionDTO> resultat = journalService.consulter(succes, utilisateurId, du, au, page, taille);
        return new PageDTO<>(resultat.getContent(), resultat.getNumber(), resultat.getSize(), resultat.getTotalElements());
    }
}
