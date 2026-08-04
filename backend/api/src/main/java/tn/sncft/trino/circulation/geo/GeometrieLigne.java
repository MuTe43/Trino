package tn.sncft.trino.circulation.geo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Position along a ligne: haversine plus linear interpolation, no PostGIS
 * (decision 5). Pure math -- no Spring, no Jackson, no database. Callers parse
 * the `trace` JSON and hand over the points.
 *
 * <h2>Two scales, mapped anchor to anchor</h2>
 *
 * A ligne has two different notions of distance and conflating them is the
 * trap this class exists to avoid:
 *
 * <ul>
 *   <li><b>pk_km</b> -- the chainage the timetable is written against. This is
 *       what {@code course.avancement_km} and {@code passage_gare.pk_km} mean,
 *       and what every caller speaks.</li>
 *   <li><b>polyline arc length</b> -- how long the stored `trace` actually is
 *       when you walk it. It is a drawn approximation of a real railway and
 *       does not equal the chainage: on the seeded network the two differ by
 *       up to 40%.</li>
 * </ul>
 *
 * Walking {@code pk} km along the polyline as if the scales matched puts a
 * train tens of kilometres from the station it is actually standing in, and
 * nothing crashes -- the map just quietly lies. So every stop is an
 * <i>anchor</i> tying its pk_km to the trace vertex that is its gare, and
 * positions are interpolated proportionally between consecutive anchors. A
 * train at a stop's pk lands exactly on that stop, whatever the polyline does
 * in between.
 */
public final class GeometrieLigne {

    /** A point on the earth, in degrees. */
    public record PointGeo(double latitude, double longitude) {
    }

    /** A stop, tying its timetable chainage to its coordinates. */
    public record Arret(double pkKm, double latitude, double longitude) {
    }

    private static final double RAYON_TERRE_KM = 6371.0;

    /**
     * How far a stop may sit from its nearest trace vertex before we refuse to
     * build the geometry. The seeded traces are generated through the stops, so
     * the real distance is ~0; anything beyond this means the trace and the
     * desserte have drifted apart and the anchoring would be meaningless.
     */
    private static final double TOLERANCE_ANCRAGE_KM = 1.0;

    private final double[] latitudes;
    private final double[] longitudes;
    /** Arc length in km from the first vertex to vertex i. */
    private final double[] cumul;
    /** pk_km of each anchor, ascending. */
    private final double[] ancragePk;
    /** Trace vertex index of each anchor, ascending. */
    private final int[] ancrageSommet;

    private GeometrieLigne(double[] latitudes, double[] longitudes, double[] cumul,
                           double[] ancragePk, int[] ancrageSommet) {
        this.latitudes = latitudes;
        this.longitudes = longitudes;
        this.cumul = cumul;
        this.ancragePk = ancragePk;
        this.ancrageSommet = ancrageSommet;
    }

    /**
     * @param trace  ordered polyline as stored in {@code ligne.trace}: a list
     *               of {@code [lon, lat]} pairs (GeoJSON order).
     * @param arrets the stops of this course, ascending by pk_km. At least two.
     */
    public static GeometrieLigne depuis(List<List<Double>> trace, List<Arret> arrets) {
        if (trace == null || trace.size() < 2) {
            throw new IllegalArgumentException("Trace invalide : au moins deux points sont requis.");
        }
        if (arrets == null || arrets.size() < 2) {
            throw new IllegalArgumentException("Desserte invalide : au moins deux arrêts sont requis.");
        }

        int n = trace.size();
        double[] lat = new double[n];
        double[] lon = new double[n];
        for (int i = 0; i < n; i++) {
            List<Double> point = trace.get(i);
            if (point == null || point.size() < 2) {
                throw new IllegalArgumentException("Point de trace invalide à l'index " + i + ".");
            }
            lon[i] = point.get(0);
            lat[i] = point.get(1);
        }

        double[] cumul = new double[n];
        for (int i = 1; i < n; i++) {
            cumul[i] = cumul[i - 1] + haversineKm(lat[i - 1], lon[i - 1], lat[i], lon[i]);
        }

        List<Arret> tries = new ArrayList<>(arrets);
        tries.sort(Comparator.comparingDouble(Arret::pkKm));

        double[] pk = new double[tries.size()];
        int[] sommets = new int[tries.size()];
        for (int a = 0; a < tries.size(); a++) {
            Arret arret = tries.get(a);
            int meilleur = -1;
            double meilleureDistance = Double.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                double d = haversineKm(arret.latitude(), arret.longitude(), lat[i], lon[i]);
                if (d < meilleureDistance) {
                    meilleureDistance = d;
                    meilleur = i;
                }
            }
            if (meilleureDistance > TOLERANCE_ANCRAGE_KM) {
                throw new IllegalStateException(String.format(
                        "Arrêt au pk %.2f à %.2f km du tracé : le tracé et la desserte ont divergé.",
                        arret.pkKm(), meilleureDistance));
            }
            pk[a] = arret.pkKm();
            sommets[a] = meilleur;
        }

        // Anchors must advance along the polyline in the same order as the
        // timetable does. If they don't, the stop order and the drawn line
        // disagree and interpolating between them would run backwards.
        for (int a = 1; a < sommets.length; a++) {
            if (sommets[a] <= sommets[a - 1] || pk[a] <= pk[a - 1]) {
                throw new IllegalStateException(
                        "Arrêts non monotones le long du tracé à l'index " + a
                                + " : ordre, pk_km et géométrie doivent progresser ensemble.");
            }
        }

        return new GeometrieLigne(lat, lon, cumul, pk, sommets);
    }

    /**
     * Position at a given chainage. {@code pkKm} is clamped to the served
     * range, so a course that overshoots its terminus renders at the terminus
     * rather than off the end of the line.
     */
    public PointGeo positionA(double pkKm) {
        double cible = Math.max(ancragePk[0], Math.min(pkKm, ancragePk[ancragePk.length - 1]));

        int a = indexAncrageAvant(cible);
        double pkDebut = ancragePk[a];
        double pkFin = ancragePk[a + 1];
        double arcDebut = cumul[ancrageSommet[a]];
        double arcFin = cumul[ancrageSommet[a + 1]];

        double fraction = (pkFin - pkDebut) == 0 ? 0 : (cible - pkDebut) / (pkFin - pkDebut);
        double arcCible = arcDebut + fraction * (arcFin - arcDebut);

        return pointAArcLength(arcCible, ancrageSommet[a], ancrageSommet[a + 1]);
    }

    /**
     * Inverse of {@link #positionA}: the chainage of the point on the ligne
     * closest to the given coordinates. This is what turns a GPS fix into an
     * {@code avancement_km} -- real AVL hardware reports latitude and
     * longitude, never chainage, so the projection has to happen server-side.
     */
    public double projeter(double latitude, double longitude) {
        double meilleureDistance = Double.MAX_VALUE;
        double meilleurArc = 0;

        for (int i = 0; i < latitudes.length - 1; i++) {
            double segment = cumul[i + 1] - cumul[i];
            if (segment <= 0) {
                continue;
            }
            double t = fractionProjetee(latitude, longitude, i);
            double lat = latitudes[i] + (latitudes[i + 1] - latitudes[i]) * t;
            double lon = longitudes[i] + (longitudes[i + 1] - longitudes[i]) * t;
            double d = haversineKm(latitude, longitude, lat, lon);
            if (d < meilleureDistance) {
                meilleureDistance = d;
                meilleurArc = cumul[i] + segment * t;
            }
        }
        return pkDepuisArc(meilleurArc);
    }

    /**
     * Length of the ligne on the timetable's scale, i.e. the chainage of the
     * last stop. This is the number every caller means by "how long is this
     * line" -- not {@link #longueurPolyligne()}.
     */
    public double longueurTotale() {
        return ancragePk[ancragePk.length - 1];
    }

    /** Walked length of the stored polyline. Diagnostics only. */
    public double longueurPolyligne() {
        return cumul[cumul.length - 1];
    }

    private int indexAncrageAvant(double pkKm) {
        for (int a = ancragePk.length - 2; a >= 0; a--) {
            if (pkKm >= ancragePk[a]) {
                return a;
            }
        }
        return 0;
    }

    /** Chainage corresponding to an arc length, inverting the anchor mapping. */
    private double pkDepuisArc(double arc) {
        for (int a = 0; a < ancrageSommet.length - 1; a++) {
            double arcDebut = cumul[ancrageSommet[a]];
            double arcFin = cumul[ancrageSommet[a + 1]];
            if (arc <= arcFin || a == ancrageSommet.length - 2) {
                double fraction = (arcFin - arcDebut) == 0 ? 0 : (arc - arcDebut) / (arcFin - arcDebut);
                fraction = Math.max(0, Math.min(1, fraction));
                return ancragePk[a] + fraction * (ancragePk[a + 1] - ancragePk[a]);
            }
        }
        return ancragePk[0];
    }

    private PointGeo pointAArcLength(double arc, int deSommet, int aSommet) {
        for (int i = deSommet; i < aSommet; i++) {
            if (arc <= cumul[i + 1] || i == aSommet - 1) {
                double segment = cumul[i + 1] - cumul[i];
                double fraction = segment == 0 ? 0 : (arc - cumul[i]) / segment;
                fraction = Math.max(0, Math.min(1, fraction));
                return new PointGeo(
                        latitudes[i] + (latitudes[i + 1] - latitudes[i]) * fraction,
                        longitudes[i] + (longitudes[i + 1] - longitudes[i]) * fraction);
            }
        }
        return new PointGeo(latitudes[aSommet], longitudes[aSommet]);
    }

    /**
     * Where the given point falls on segment i, as a fraction in [0,1]. Solved
     * in a local flat approximation: over a segment of a few km the error is
     * far below the precision anything downstream cares about.
     */
    private double fractionProjetee(double latitude, double longitude, int i) {
        double cosLat = Math.cos(Math.toRadians(latitude));
        double ax = (longitudes[i] - longitude) * cosLat;
        double ay = latitudes[i] - latitude;
        double bx = (longitudes[i + 1] - longitudes[i]) * cosLat;
        double by = latitudes[i + 1] - latitudes[i];

        double denominateur = bx * bx + by * by;
        if (denominateur == 0) {
            return 0;
        }
        double t = -(ax * bx + ay * by) / denominateur;
        return Math.max(0, Math.min(1, t));
    }

    /** Great-circle distance in km. */
    public static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * RAYON_TERRE_KM * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }
}
