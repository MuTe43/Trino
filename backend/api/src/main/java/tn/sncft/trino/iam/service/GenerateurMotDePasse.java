package tn.sncft.trino.iam.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Generates the one-time initial password handed to a newly created account.
 * The alphabet drops the glyphs a human reading it aloud and retyping it
 * would confuse -- {@code O 0 o l I 1} -- since that is exactly how this
 * password is transmitted out of band.
 */
@Component
public class GenerateurMotDePasse {

    private static final String ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";

    private static final int LONGUEUR = 14;

    private final SecureRandom random = new SecureRandom();

    public String generer() {
        StringBuilder motDePasse = new StringBuilder(LONGUEUR);
        for (int i = 0; i < LONGUEUR; i++) {
            motDePasse.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return motDePasse.toString();
    }
}
