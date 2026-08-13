package tn.sncft.trino.securite;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * The anonymous subscriber token: 32 bytes from {@link SecureRandom}, base64url,
 * no padding.
 *
 * <p>It is a bearer credential for one passenger's subscription list, not a
 * display id. Whoever holds it can read that person's notifications and cancel
 * their subscriptions, so it is never logged, never put in a URL path or query
 * string, and never returned in a response body -- it reaches the browser as an
 * {@code HttpOnly} cookie and nothing else. A guessable token would be worth
 * guessing, which is why this is {@code SecureRandom} and not
 * {@code UUID.randomUUID()} shortened or {@code Math.random}.
 *
 * <p>A client may also present a token of its own (the acceptance script does).
 * That is fine -- the value is only ever a key into that same client's own
 * rows -- but it still has to pass {@link #valide}, because the column is
 * 64 characters and uniquely indexed and unvalidated input would otherwise
 * decide what lands in it.
 */
public final class JetonAbonne {

    /** Read by {@code StreamController} too, so the SSE channel binds off the same cookie. */
    public static final String NOM_COOKIE = "jeton_abonne";

    /** The header form, for API clients that cannot hold a cookie. */
    public static final String NOM_ENTETE = "X-Abonne";

    private static final SecureRandom ALEA = new SecureRandom();
    private static final Base64.Encoder ENCODEUR = Base64.getUrlEncoder().withoutPadding();

    /** Rejects anything that could not have come out of {@link #generer}, and stays inside varchar(64). */
    private static final int LONGUEUR_MIN = 16;
    private static final int LONGUEUR_MAX = 64;

    private JetonAbonne() {
    }

    public static String generer() {
        byte[] octets = new byte[32];
        ALEA.nextBytes(octets);
        return ENCODEUR.encodeToString(octets);
    }

    public static boolean valide(String jeton) {
        if (jeton == null || jeton.length() < LONGUEUR_MIN || jeton.length() > LONGUEUR_MAX) {
            return false;
        }
        for (int i = 0; i < jeton.length(); i++) {
            char c = jeton.charAt(i);
            boolean autorise = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!autorise) {
                return false;
            }
        }
        return true;
    }
}
