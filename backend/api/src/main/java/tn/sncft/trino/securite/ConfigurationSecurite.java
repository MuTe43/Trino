package tn.sncft.trino.securite;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
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

    /**
     * Frontend origins allowed to call the API. A list, not a single string,
     * because phase 7's docker-compose needs to allow more than one origin at
     * once; overridable via {@code TRINO_CORS_ORIGINES} (comma-separated) so
     * serving the frontend from a port other than 3000 does not require
     * editing this file.
     */
    private final List<String> origines;

    public ConfigurationSecurite(ObjectMapper objectMapper,
                                 @Value("${trino.cors.origines}") List<String> origines) {
        this.objectMapper = objectMapper;
        this.origines = origines;
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
    public FiltreCleIngestion filtreCleIngestion(@Value("${trino.ingestion.cle}") String cleIngestion) {
        return new FiltreCleIngestion(cleIngestion, objectMapper);
    }

    /**
     * Keeps the two filters above out of the servlet container's own chain.
     *
     * <p>They are {@code @Bean}s of type {@code Filter}, and Boot auto-registers
     * any such bean with the container. Spring Security also places them in its
     * chain -- which is where {@link #chaineFiltres} puts them, at a position it
     * controls -- so since phase 1 every request has run both filters twice, and
     * the container copy has run on paths the security chain never sees,
     * {@code /error} among them.
     *
     * <p>Both extend {@code OncePerRequestFilter}, so the second pass was a
     * wasted JWT parse rather than a wrong answer. That is precisely why it
     * survived eight phases: nothing about the responses looked different.
     *
     * <p>Declared here rather than in {@code ConfigurationWeb}, next to the beans
     * they correct and away from the {@code WebMvcConfigurer} that every
     * {@code @WebMvcTest} slice loads.
     */
    @Bean
    public FilterRegistrationBean<FiltreJwt> enregistrementFiltreJwt(FiltreJwt filtreJwt) {
        return sansEnregistrementAutomatique(filtreJwt);
    }

    @Bean
    public FilterRegistrationBean<FiltreCleIngestion> enregistrementFiltreCleIngestion(
            FiltreCleIngestion filtreCleIngestion) {
        return sansEnregistrementAutomatique(filtreCleIngestion);
    }

    private static <T extends Filter> FilterRegistrationBean<T> sansEnregistrementAutomatique(T filtre) {
        FilterRegistrationBean<T> enregistrement = new FilterRegistrationBean<>(filtre);
        enregistrement.setEnabled(false);
        return enregistrement;
    }

    @Bean
    public SecurityFilterChain chaineFiltres(HttpSecurity http, FiltreJwt filtreJwt,
                                             FiltreCleIngestion filtreCleIngestion) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(sourceCors()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Boot's own fallback error page. Normally never reached -- every
                        // exception on a synchronous request is already resolved by
                        // ApiExceptionHandler -- except when Tomcat's async machinery
                        // auto-forwards here on an async context nobody completed (an SSE
                        // stream that outlives its client, notably). Anonymous callers to a
                        // permitAll endpoint like /stream/** must not fail authorization a
                        // second time trying to reach the page reporting the first failure.
                        .requestMatchers("/error").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/refresh").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/gares/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/lignes/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/trains/**").permitAll()
                        // Circulation reads are anonymous for the same reason as the référentiel:
                        // a passenger checking whether their train is late has no account. The
                        // gares rule above already covers /gares/{id}/departs.
                        .requestMatchers(HttpMethod.GET, "/api/v1/courses/**", "/api/v1/recherche").permitAll()
                        // SSE channels are anonymous per api-contract.md (passenger portal, station
                        // boards). No controller exists for them yet, but the rule stands regardless
                        // of build order: don't let the anyRequest().authenticated() default catch them.
                        // Both patterns on purpose: the multiplexed stream is /stream with no path
                        // segment after it, and relying on "/stream/**" to match the bare path is a
                        // matcher-implementation detail, not something to bet anonymous access on.
                        .requestMatchers(HttpMethod.GET, "/api/v1/stream", "/api/v1/stream/**").permitAll()
                        // Following a train needs no account (phase 8). A passenger
                        // checking on their train has no login, so requiring one here
                        // would deliver the notification use case to nobody who wants
                        // it. These endpoints are not unprotected for that -- they are
                        // scoped by the caller's own credential inside the service, and
                        // POST is rate-limited per IP because it is an unauthenticated
                        // write that sends mail.
                        .requestMatchers("/api/v1/abonnements", "/api/v1/abonnements/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/notifications").permitAll()
                        .requestMatchers("/actuator/**").permitAll()
                        // The position feed authenticates with X-Ingest-Key, checked by
                        // FiltreCleIngestion ahead of this rule. The rule stays so the
                        // endpoints are never reachable anonymously if the filter is ever
                        // unregistered -- same belt-and-braces as the référentiel writes.
                        .requestMatchers("/api/v1/ingest/**").hasRole("INGESTION")
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
                        // Dashboards and reports: RESPONSABLE_EXPLOITATION, and only that
                        // role. ADMINISTRATEUR is NOT a superuser here -- it administers the
                        // référentiel, the accounts and the connection log; reading
                        // operational analytics is a different duty and stays separate.
                        // Paired with @PreAuthorize on the services (invariant 9): the URL
                        // rule runs in the filter chain, ahead of argument binding, so a
                        // caller who may not touch the endpoint gets 403 rather than a 400
                        // telling them their date format was wrong.
                        .requestMatchers(HttpMethod.GET, "/api/v1/tableau-bord/**", "/api/v1/rapports/**")
                        .hasRole("RESPONSABLE_EXPLOITATION")
                        // Incidents. This rule MUST stay above the general
                        // /incidents/** ones: matchers are evaluated in order and
                        // the first match wins, so the broader POST rule below
                        // would otherwise let an agent resolve.
                        //
                        // Resolution is its own URL precisely so this check can
                        // live here, in the filter chain. Expressed instead as
                        // "PATCH with statut=RESOLU", the distinction would be a
                        // body value, which the filter chain cannot see -- and an
                        // agent sending a malformed body that also asked to
                        // resolve would get 400 VALIDATION_ECHOUEE instead of
                        // 403, the exact failure invariant 9 documents.
                        .requestMatchers(HttpMethod.POST, "/api/v1/incidents/*/resolution")
                        .hasRole("RESPONSABLE_EXPLOITATION")
                        // Declaring and editing: an agent's job, and the
                        // responsable's too. ADMINISTRATEUR is excluded for the
                        // same reason as the dashboards -- a different duty.
                        .requestMatchers(HttpMethod.POST, "/api/v1/incidents", "/api/v1/incidents/**")
                        .hasAnyRole("AGENT_CIRCULATION", "RESPONSABLE_EXPLOITATION")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/incidents/**")
                        .hasAnyRole("AGENT_CIRCULATION", "RESPONSABLE_EXPLOITATION")
                        // Reads are not public: the passenger portal learns about
                        // incidents from the ligne SSE channel, never from this
                        // list, which carries who declared what.
                        .requestMatchers(HttpMethod.GET, "/api/v1/incidents", "/api/v1/incidents/**")
                        .hasAnyRole("AGENT_CIRCULATION", "RESPONSABLE_EXPLOITATION")
                        // User administration and the connection log: ADMINISTRATEUR only.
                        // Paired with @PreAuthorize on the services (invariant 9) -- the URL
                        // rule runs in the filter chain, ahead of @Valid on the request body,
                        // so a forbidden caller with a malformed body still sees 403, not 400.
                        .requestMatchers(HttpMethod.POST, "/api/v1/utilisateurs", "/api/v1/utilisateurs/**")
                        .hasRole("ADMINISTRATEUR")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/utilisateurs/**")
                        .hasRole("ADMINISTRATEUR")
                        .requestMatchers(HttpMethod.GET, "/api/v1/utilisateurs", "/api/v1/utilisateurs/**")
                        .hasRole("ADMINISTRATEUR")
                        .requestMatchers(HttpMethod.GET, "/api/v1/journal-connexions", "/api/v1/journal-connexions/**")
                        .hasRole("ADMINISTRATEUR")
                        // Alert rules: ADMINISTRATEUR only, and this is the half of
                        // invariant 9 that produces the right status. POST and PATCH
                        // carry validated bodies, so without a URL rule a forbidden
                        // caller sending a malformed payload would be answered 400
                        // VALIDATION_ECHOUEE -- told their payload was wrong on an
                        // endpoint they were never allowed to touch. @PreAuthorize on
                        // ServiceRegleAlerte is the other half.
                        .requestMatchers("/api/v1/regles-alerte", "/api/v1/regles-alerte/**")
                        .hasRole("ADMINISTRATEUR")
                        .anyRequest().authenticated())
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(pointEntreeAuthentification())
                        .accessDeniedHandler(gestionnaireAccesRefuse()))
                .addFilterBefore(filtreJwt, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(filtreCleIngestion, FiltreJwt.class);
        return http.build();
    }

    private CorsConfigurationSource sourceCors() {
        // Credentials are allowed, so a wildcard origin is illegal. Spring only
        // discovers that per request, throwing IllegalArgumentException inside
        // checkOrigin -- every preflight 500s and the cause is nowhere near the
        // configuration that produced it. Since phase 7 sets this from the
        // environment and "*" is the first thing anyone reaches for, fail at
        // startup with a message that names the fix instead.
        if (origines.stream().anyMatch(origine -> origine.contains("*"))) {
            throw new IllegalStateException(
                    "trino.cors.origines n'accepte pas de joker (\"*\") : les requêtes sont envoyées "
                            + "avec des identifiants. Indiquez les origines exactes, séparées par des "
                            + "virgules, par exemple http://localhost:3000,http://localhost:3001.");
        }
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(origines);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        // X-Abonne since phase 8: the browser normally sends the subscriber token
        // as a cookie, but an API client that cannot hold one uses the header,
        // and a header not listed here is stripped by the preflight.
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Abonne"));
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
