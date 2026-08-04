package tn.sncft.trino.circulation.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tn.sncft.trino.circulation.dto.PassageDTO;
import tn.sncft.trino.circulation.service.DepartsService;

import java.util.List;

/**
 * The station departure board. Lives in circulation rather than référentiel
 * because what it serves is a run, not a station's own data.
 */
@RestController
@RequestMapping("/api/v1/gares")
public class DepartsController {

    private final DepartsService departsService;

    public DepartsController(DepartsService departsService) {
        this.departsService = departsService;
    }

    @GetMapping("/{id}/departs")
    public List<PassageDTO> departs(@PathVariable Long id,
                                    @RequestParam(defaultValue = "20") int limite) {
        return departsService.prochainsDeparts(id, limite);
    }
}
