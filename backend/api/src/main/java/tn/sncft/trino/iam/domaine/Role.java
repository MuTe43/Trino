package tn.sncft.trino.iam.domaine;

/**
 * Application roles. These exact names are load-bearing: other modules gate
 * on them by string (Spring authorities are "ROLE_" + name()). Do not rename.
 */
public enum Role {
    VOYAGEUR,
    AGENT_CIRCULATION,
    RESPONSABLE_EXPLOITATION,
    ADMINISTRATEUR
}
