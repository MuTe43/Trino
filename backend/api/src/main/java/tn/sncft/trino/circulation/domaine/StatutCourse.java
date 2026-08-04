package tn.sncft.trino.circulation.domaine;

/**
 * Status of a dated run. Lives on the Course, never on the Train: the same
 * trainset runs Tunis-Sousse in the morning and the return at night, and those
 * two runs have two different statuses.
 *
 * <p>Transitions are owned by MachineEtatCourse in phase 3. Nothing in phase 2
 * changes this field after GenerateurCourses sets the initial A_QUAI.
 */
public enum StatutCourse {
    A_QUAI,
    EN_CIRCULATION,
    RETARDE,
    ARRET_EXCEPTIONNEL,
    ANNULE,
    TERMINUS_ATTEINT
}
