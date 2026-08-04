package tn.sncft.trino.circulation.geo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tn.sncft.trino.referentiel.evenement.GareModifiee;
import tn.sncft.trino.referentiel.evenement.LigneModifiee;
import tn.sncft.trino.circulation.domaine.Course;
import tn.sncft.trino.circulation.domaine.PassageGare;
import tn.sncft.trino.circulation.domaine.SensCourse;
import tn.sncft.trino.referentiel.domaine.Ligne;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds and caches the {@link GeometrieLigne} of a course.
 *
 * <p>The geometry depends only on the ligne and the direction -- every ALLER
 * course on L1 anchors the same stops to the same polyline -- so it is cached
 * on that key rather than per course. Parsing a 40-point trace and running the
 * anchor search on every ingested ping would otherwise be the most expensive
 * thing on the hot path, for a result that never changes within a service day.
 */
@Component
public class FabriqueGeometrie {

    private final ObjectMapper objectMapper;
    private final Map<String, GeometrieLigne> cache = new ConcurrentHashMap<>();

    public FabriqueGeometrie(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * @param passages the stops of this course, ordered, with their gare loaded
     */
    public GeometrieLigne pour(Course course, List<PassageGare> passages) {
        String cle = course.getLigne().getId() + "|" + course.getSens();
        return cache.computeIfAbsent(cle, ignore -> construire(course, passages));
    }

    /**
     * Drops the cache when a ligne changes. Without this the feed keeps
     * projecting against the polyline the ligne had at startup: an admin edits
     * a trace, every response still says 200, and the trains quietly sit on
     * the old line until someone restarts the API.
     *
     * <p>AFTER_COMMIT, not a plain listener. A plain one fires inside the
     * publisher's transaction, so an ingest landing between the eviction and
     * the commit would rebuild from the old committed trace and re-cache it --
     * leaving exactly the stale entry this is here to remove, and surviving
     * until restart. It also means a write that ends up rolling back (a
     * duplicate code, say) no longer evicts for a change that never happened.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void surLigneModifiee(LigneModifiee evenement) {
        cache.keySet().removeIf(cle -> cle.startsWith(evenement.ligneId() + "|"));
    }

    /**
     * A gare's coordinates are what every anchor is pinned to, so moving one
     * invalidates the geometry of every ligne serving it. Resolving which
     * lignes those are would mean a query from here; the cache holds one entry
     * per ligne and direction, so clearing it outright is cheaper than finding
     * out.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void surGareModifiee(GareModifiee evenement) {
        cache.clear();
    }

    /** Drops the whole cache. */
    public void vider() {
        cache.clear();
    }

    /** The ligne's polyline as [lon,lat] pairs, for callers that ship it out. */
    public List<List<Double>> trace(Ligne ligne) {
        return deserialiserTrace(ligne.getTrace());
    }

    private GeometrieLigne construire(Course course, List<PassageGare> passages) {
        List<List<Double>> trace = deserialiserTrace(course.getLigne().getTrace());

        // The trace is stored once, in the ALLER direction. A RETOUR course
        // has its stops mirrored to ascending pk, so the polyline has to be
        // walked the other way round or the anchors run backwards along it and
        // the whole mapping inverts.
        if (course.getSens() == SensCourse.RETOUR) {
            trace = new ArrayList<>(trace);
            Collections.reverse(trace);
        }

        List<GeometrieLigne.Arret> arrets = passages.stream()
                .map(p -> new GeometrieLigne.Arret(
                        p.getPkKm().doubleValue(),
                        p.getGare().getLatitude().doubleValue(),
                        p.getGare().getLongitude().doubleValue()))
                .toList();
        return GeometrieLigne.depuis(trace, arrets);
    }

    private List<List<Double>> deserialiserTrace(String trace) {
        if (trace == null || trace.isBlank()) {
            throw new IllegalStateException("Ligne sans tracé : impossible de positionner un train.");
        }
        try {
            return objectMapper.readValue(trace, new TypeReference<List<List<Double>>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Tracé stocké invalide", e);
        }
    }
}
