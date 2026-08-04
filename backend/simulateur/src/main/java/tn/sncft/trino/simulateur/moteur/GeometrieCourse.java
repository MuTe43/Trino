package tn.sncft.trino.simulateur.moteur;

import tn.sncft.trino.simulateur.dto.CourseDuJourDTO;

import java.util.List;

/**
 * Turns a chainage into coordinates, so the simulator can emit a GPS fix the
 * way hardware would.
 *
 * <p>This duplicates the API's GeometrieLigne on purpose. The two processes are
 * coupled by the HTTP contract and nothing else -- real AVL equipment computes
 * its own position from its own sensors and does not link against Trino. A
 * shared jar here would be the first step back towards the "@Scheduled bean in
 * the domain layer" design that decision 2 rejects.
 *
 * <p>Like the API's copy, stops are anchors: a stop's pk_km is tied to the
 * trace vertex that is its gare, and positions interpolate proportionally
 * between anchors. The polyline's own length is not the line's chainage.
 */
final class GeometrieCourse {

    private static final double RAYON_TERRE_KM = 6371.0;

    /** How far a stop may sit from its nearest trace vertex before we refuse. */
    private static final double TOLERANCE_ANCRAGE_KM = 1.0;

    private final double[] latitudes;
    private final double[] longitudes;
    private final double[] cumul;
    private final double[] ancragePk;
    private final int[] ancrageSommet;

    private GeometrieCourse(double[] latitudes, double[] longitudes, double[] cumul,
                            double[] ancragePk, int[] ancrageSommet) {
        this.latitudes = latitudes;
        this.longitudes = longitudes;
        this.cumul = cumul;
        this.ancragePk = ancragePk;
        this.ancrageSommet = ancrageSommet;
    }

    static GeometrieCourse depuis(CourseDuJourDTO course) {
        List<List<Double>> trace = course.trace();
        List<CourseDuJourDTO.ArretDTO> arrets = course.desserte();
        if (trace == null || trace.size() < 2 || arrets == null || arrets.size() < 2) {
            throw new IllegalArgumentException("Course " + course.courseId() + " : géométrie inexploitable.");
        }

        // The trace is published in the ALLER direction only. A RETOUR course
        // receives its stops already mirrored to ascending pk, so the polyline
        // has to be walked the other way round to match.
        boolean retour = "RETOUR".equals(course.sens());

        int n = trace.size();
        double[] lat = new double[n];
        double[] lon = new double[n];
        for (int i = 0; i < n; i++) {
            List<Double> point = trace.get(retour ? n - 1 - i : i);
            lon[i] = point.get(0);
            lat[i] = point.get(1);
        }

        double[] cumul = new double[n];
        for (int i = 1; i < n; i++) {
            cumul[i] = cumul[i - 1] + haversineKm(lat[i - 1], lon[i - 1], lat[i], lon[i]);
        }

        double[] pk = new double[arrets.size()];
        int[] sommets = new int[arrets.size()];
        for (int a = 0; a < arrets.size(); a++) {
            CourseDuJourDTO.ArretDTO arret = arrets.get(a);
            int meilleur = 0;
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
                        "Course %d : arrêt %s à %.2f km du tracé.",
                        course.courseId(), arret.code(), meilleureDistance));
            }
            pk[a] = arret.pkKm();
            sommets[a] = meilleur;
        }

        // The same guards the API's GeometrieLigne carries. Without them a
        // non-monotone anchor set does not fail -- positionA's inner loop
        // simply never runs and returns a fixed vertex, so the train sits
        // motionless on the map while its chainage climbs, and nothing is
        // logged. MoteurSimulation.recharger catches this and skips the
        // course, which is a great deal louder than a stuck train.
        for (int a = 1; a < sommets.length; a++) {
            if (sommets[a] <= sommets[a - 1] || pk[a] <= pk[a - 1]) {
                throw new IllegalStateException(String.format(
                        "Course %d : arrêts non monotones le long du tracé à l'index %d.",
                        course.courseId(), a));
            }
        }

        return new GeometrieCourse(lat, lon, cumul, pk, sommets);
    }

    /** Chainage of the terminus. */
    double longueurTotale() {
        return ancragePk[ancragePk.length - 1];
    }

    /** Coordinates at a chainage, clamped to the served range. */
    double[] positionA(double pkKm) {
        double cible = Math.max(ancragePk[0], Math.min(pkKm, ancragePk[ancragePk.length - 1]));

        int a = 0;
        for (int i = ancragePk.length - 2; i >= 0; i--) {
            if (cible >= ancragePk[i]) {
                a = i;
                break;
            }
        }

        double etendue = ancragePk[a + 1] - ancragePk[a];
        double fraction = etendue == 0 ? 0 : (cible - ancragePk[a]) / etendue;
        double arcDebut = cumul[ancrageSommet[a]];
        double arcCible = arcDebut + fraction * (cumul[ancrageSommet[a + 1]] - arcDebut);

        for (int i = ancrageSommet[a]; i < ancrageSommet[a + 1]; i++) {
            if (arcCible <= cumul[i + 1] || i == ancrageSommet[a + 1] - 1) {
                double segment = cumul[i + 1] - cumul[i];
                double f = segment == 0 ? 0 : (arcCible - cumul[i]) / segment;
                f = Math.max(0, Math.min(1, f));
                return new double[]{
                        latitudes[i] + (latitudes[i + 1] - latitudes[i]) * f,
                        longitudes[i] + (longitudes[i + 1] - longitudes[i]) * f
                };
            }
        }
        return new double[]{latitudes[ancrageSommet[a + 1]], longitudes[ancrageSommet[a + 1]]};
    }

    private static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * RAYON_TERRE_KM * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }
}
