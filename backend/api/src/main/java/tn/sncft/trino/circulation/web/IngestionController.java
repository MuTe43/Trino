package tn.sncft.trino.circulation.web;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tn.sncft.trino.circulation.dto.CourseDuJourDTO;
import tn.sncft.trino.circulation.dto.LotPingsDTO;
import tn.sncft.trino.circulation.dto.ResultatIngestionDTO;
import tn.sncft.trino.circulation.service.IngestionService;

import java.util.List;

/**
 * The position-feed endpoints. Authenticated by {@code X-Ingest-Key} alone --
 * the producer has no user, no JWT and no session, because a GPS box does not
 * log in.
 */
@RestController
@RequestMapping("/api/v1/ingest")
public class IngestionController {

    private final IngestionService ingestionService;

    public IngestionController(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @GetMapping("/courses-du-jour")
    public List<CourseDuJourDTO> coursesDuJour() {
        return ingestionService.coursesDuJour();
    }

    @PostMapping("/positions")
    public ResponseEntity<ResultatIngestionDTO> positions(@Valid @RequestBody LotPingsDTO lot) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ingestionService.ingerer(lot));
    }
}
