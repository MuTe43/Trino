package tn.sncft.trino.circulation.domaine;

/**
 * Delay bucket, derived from {@code retardMin} and never stored. The thresholds
 * are the ones in docs/architecture/domain-model.md; keeping them in one place
 * stops the dashboard, the station board and the reports from each drawing
 * their own boundary between "on time" and "late".
 *
 * <p>A negative delay (a train running early) classifies as {@link #A_L_HEURE}.
 */
public enum ClasseRetard {

    A_L_HEURE,
    R5,
    R10,
    R15,
    R30,
    R60_PLUS;

    public static ClasseRetard de(int retardMin) {
        if (retardMin < 5) {
            return A_L_HEURE;
        }
        if (retardMin < 10) {
            return R5;
        }
        if (retardMin < 15) {
            return R10;
        }
        if (retardMin < 30) {
            return R15;
        }
        if (retardMin < 60) {
            return R30;
        }
        return R60_PLUS;
    }
}
