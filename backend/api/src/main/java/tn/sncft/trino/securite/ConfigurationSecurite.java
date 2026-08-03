package tn.sncft.trino.securite;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tn.sncft.trino.commun.ErreurDTO;
import tn.sncft.trino.iam.service.JetonService;
import tn.sncft.trino.iam.service.UtilisateurService;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Stateless JWT security setup. Reads are open for the référentiel and auth
 * endpoints; everything else requires authentication, with per-method
 * {@code @PreAuthorize} enforcing role checks (enabled via
 * {@code @EnableMethodSecurity}). Both the 401 and 403 paths write the same
 * error envelope as {@link tn.sncft.trino.commun.ApiExceptionHandler}.
 */
@Configuration
@EnableMethodSecurity
public class ConfigurationSecurite {

    private final ObjectMapper objectMapper;

    public ConfigurationSecurite(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Bean
    public BCryptPasswordEncoder motDePasseEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public FiltreJwt filtreJwt(JetonService jetonService, UtilisateurService utilisateurService) {
        return new FiltreJwt(jetonService, utilisateurService);
    }

    @Bean
    public SecurityFilterChain chaineFiltres(HttpSecurity http, FiltreJwt filtreJwt) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(sourceCors()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/refresh").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/gares/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/lignes/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/trains/**").permitAll()
                        // SSE channels are anonymous per api-contract.md (passenger portal, station
                        // boards). No controller exists for them yet, but the rule stands regardless
                        // of build order: don't let the anyRequest().authenticated() default catch them.
                        .requestMatchers(HttpMethod.GET, "/api/v1/stream/**").permitAll()
                        .requestMatchers("/actuator/**").permitAll()
                        // Enforced here too (not just @PreAuthorize on the services): otherwise
                        // @Valid on the request body runs during controller argument resolution,
                        // before the service (and its @PreAuthorize) is ever called, and an
                        // unauthorized caller with a malformed body would see 400 instead of 403.
                        .requestMatchers(HttpMethod.POST, "/api/v1/gares/**", "/api/v1/lignes/**", "/api/v1/trains/**")
                        .hasRole("ADMINISTRATEUR")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/gares/**", "/api/v1/lignes/**", "/api/v1/trains/**")
                        .hasRole("ADMINISTRATEUR")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/gares/**", "/api/v1/lignes/**", "/api/v1/trains/**")
                        .hasRole("ADMINISTRATEUR")
                        .anyRequest().authenticated())
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(pointEntreeAuthentification())
                        .accessDeniedHandler(gestionnaireAccesRefuse()))
                .addFilterBefore(filtreJwt, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private CorsConfigurationSource sourceCors() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of("http://localhost:3000"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    private AuthenticationEntryPoint pointEntreeAuthentification() {
        return (request, response, authException) ->
                ecrireErreur(response, HttpStatus.UNAUTHORIZED, "NON_AUTHENTIFIE", "Authentification requise.");
    }

    private AccessDeniedHandler gestionnaireAccesRefuse() {
        return (request, response, accessDeniedException) ->
                ecrireErreur(response, HttpStatus.FORBIDDEN, "ACCES_REFUSE", "Accès refusé.");
    }

    private void ecrireErreur(HttpServletResponse response, HttpStatus statut, String code, String message)
            throws IOException {
        ErreurDTO erreur = new ErreurDTO(OffsetDateTime.now(ZoneOffset.UTC), statut.value(), code, message, List.of());
        response.setStatus(statut.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(erreur));
    }
}
