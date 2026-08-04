package tn.sncft.trino.circulation.web;

import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tn.sncft.trino.circulation.domaine.StatutCourse;
import tn.sncft.trino.circulation.dto.CourseResumeDTO;
import tn.sncft.trino.circulation.dto.PassageDTO;
import tn.sncft.trino.circulation.dto.PositionDTO;
import tn.sncft.trino.circulation.service.CourseService;
import tn.sncft.trino.commun.dto.PageDTO;
import tn.sncft.trino.referentiel.domaine.TypeTrain;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * REST reads for circulation. Holds no business logic, only maps requests to
 * the service layer.
 */
@RestController
@RequestMapping("/api/v1")
public class CourseController {

    private final CourseService courseService;

    public CourseController(CourseService courseService) {
        this.courseService = courseService;
    }

    @GetMapping("/courses")
    public PageDTO<CourseResumeDTO> lister(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Long ligneId,
            @RequestParam(required = false) Long gareId,
            @RequestParam(required = false) StatutCourse statut,
            @RequestParam(required = false) TypeTrain type,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int taille) {
        return versPage(courseService.lister(date, ligneId, gareId, statut, type, q, page, taille));
    }

    @GetMapping("/courses/{id}")
    public CourseResumeDTO trouverParId(@PathVariable Long id) {
        return courseService.trouverParId(id);
    }

    @GetMapping("/courses/{id}/passages")
    public List<PassageDTO> passages(@PathVariable Long id) {
        return courseService.passages(id);
    }

    @GetMapping("/courses/{id}/positions")
    public List<PositionDTO> positions(
            @PathVariable Long id,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime depuis) {
        return courseService.positions(id, depuis);
    }

    /** Unified search over train number and name, ligne, gare and destination. */
    @GetMapping("/recherche")
    public PageDTO<CourseResumeDTO> rechercher(
            @RequestParam String q,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int taille) {
        return versPage(courseService.rechercher(q, date, page, taille));
    }

    private PageDTO<CourseResumeDTO> versPage(Page<CourseResumeDTO> resultat) {
        return new PageDTO<>(resultat.getContent(), resultat.getNumber(),
                resultat.getSize(), resultat.getTotalElements());
    }
}
