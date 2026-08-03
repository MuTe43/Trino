package tn.sncft.trino.iam.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tn.sncft.trino.iam.domaine.Utilisateur;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;

/**
 * Issues and parses JWTs (access tokens) and opaque refresh tokens. The
 * refresh token itself is never a JWT: it is a random opaque string whose
 * SHA-256 hash is what gets persisted in {@code refresh_token}.
 */
@Service
public class JetonService {

    private final SecretKey cleSecrete;
    private final long accessExpirationMinutes;
    private final long refreshExpirationDays;
    private final SecureRandom generateurAleatoire = new SecureRandom();

    public JetonService(@Value("${trino.jwt.secret}") String secret,
                         @Value("${trino.jwt.access-expiration-minutes}") long accessExpirationMinutes,
                         @Value("${trino.jwt.refresh-expiration-days}") long refreshExpirationDays) {
        this.cleSecrete = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessExpirationMinutes = accessExpirationMinutes;
        this.refreshExpirationDays = refreshExpirationDays;
    }

    public String genererAccessToken(Utilisateur utilisateur) {
        Instant maintenant = Instant.now();
        return Jwts.builder()
                .subject(utilisateur.getEmail())
                .claim("role", utilisateur.getRole().name())
                .issuedAt(Date.from(maintenant))
                .expiration(Date.from(maintenant.plus(accessExpirationMinutes, ChronoUnit.MINUTES)))
                .signWith(cleSecrete, Jwts.SIG.HS256)
                .compact();
    }

    public String genererRefreshTokenBrut() {
        byte[] octets = new byte[32];
        generateurAleatoire.nextBytes(octets);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(octets);
    }

    public String hacherRefreshToken(String brut) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(brut.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 non disponible", e);
        }
    }

    public String extraireEmail(String accessToken) {
        return analyser(accessToken).getSubject();
    }

    public boolean estValide(String accessToken) {
        try {
            analyser(accessToken);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public long getRefreshExpirationDays() {
        return refreshExpirationDays;
    }

    private Claims analyser(String token) {
        return Jwts.parser()
                .verifyWith(cleSecrete)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
