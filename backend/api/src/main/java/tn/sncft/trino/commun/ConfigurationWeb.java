package tn.sncft.trino.commun;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

/**
 * Where cross-cutting request rules that are not security rules are registered.
 *
 * <p>The three rate limits, in interceptors rather than in the controllers
 * because a controller holds no logic (invariant 7) and rather than in the
 * services because a service has no business knowing a caller's IP address.
 *
 * <p><b>Nothing that needs a collaborator may be declared here.</b> This class is
 * a {@code WebMvcConfigurer}, so every {@code @WebMvcTest} slice in the suite
 * loads it, and those slices do not load plain components. A {@code @Bean} method
 * here taking so much as a filter as a parameter fails the context of fifteen
 * tests that have nothing to do with web configuration -- measured, in phase 9,
 * by doing exactly that. It is why {@link LimiteurDebit} is constructed rather
 * than injected, and why the {@code FilterRegistrationBean} overrides added in
 * the same phase live in {@code ConfigurationSecurite}, beside the filters they
 * correct.
 *
 * <p>All three limits {@code api-contract.md} declares are now real. The
 * {@code /ingest/*} and {@code /auth/login} ones were written into that document
 * in phase 0 and stayed unimplemented until phase 9, which is worse than not
 * declaring them: a contract that states a limit nobody enforces tells an
 * integrator they are protected when they are not.
 */
@Configuration
public class ConfigurationWeb implements WebMvcConfigurer {

    /**
     * The phase file's figure. It is an unauthenticated write that sends mail:
     * without a limit, one loop turns this endpoint into a way to post a
     * thousand messages to any address someone cares to name.
     */
    private static final int MAX_ABONNEMENTS_PAR_MINUTE = 10;

    /**
     * Per ingest key, from api-contract.md. Generous on purpose: the load test
     * measured one POST every five seconds carrying 320 positions, so a real
     * fleet sits at a fraction of this and only a runaway producer reaches it.
     * The limit exists to stop one misconfigured box from crowding out the
     * others, not to shape normal traffic.
     */
    private static final int MAX_INGEST_PAR_MINUTE = 120;

    /**
     * Per IP, from api-contract.md. This is the credential-stuffing limit: ten
     * attempts a minute is far above a person mistyping their password and far
     * below anything that makes a dictionary worth running.
     */
    private static final int MAX_LOGIN_PAR_MINUTE = 10;

    /**
     * Constructed here rather than injected: this class is itself the only
     * consumer, and a {@code WebMvcConfigurer} that needs a scanned collaborator
     * fails the context of every {@code @WebMvcTest} slice in the suite -- which
     * loads configurers but not plain components.
     */
    private final LimiteurDebit limiteurDebit = new LimiteurDebit();

    @Bean
    public LimiteurDebit limiteurDebit() {
        return limiteurDebit;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registre) {
        registre.addInterceptor(new LimiteAbonnements(limiteurDebit))
                .addPathPatterns("/api/v1/abonnements");
        registre.addInterceptor(new LimiteIngestion(limiteurDebit))
                .addPathPatterns("/api/v1/ingest/**");
        registre.addInterceptor(new LimiteConnexion(limiteurDebit))
                .addPathPatterns("/api/v1/auth/login");
    }

    /**
     * Per ingest key, not per IP: the feed authenticates with {@code X-Ingest-Key}
     * and several GPS boxes behind one NAT would otherwise share a budget. A
     * request with no key at all is left to {@code FiltreCleIngestion}, which
     * rejects it — counting it here would let an unauthenticated caller consume
     * a real producer's allowance by guessing nothing at all.
     */
    private record LimiteIngestion(LimiteurDebit limiteurDebit) implements HandlerInterceptor {

        @Override
        public boolean preHandle(HttpServletRequest requete, HttpServletResponse reponse, Object handler) {
            String cleIngestion = requete.getHeader("X-Ingest-Key");
            if (cleIngestion == null || cleIngestion.isBlank()) {
                return true;
            }
            // Hashed, never the key itself: this string is a map key held in
            // memory and named in any heap dump, and the ingest key is a shared
            // secret.
            String cle = "ingest:" + Integer.toHexString(cleIngestion.hashCode());
            if (!limiteurDebit.autoriser(cle, MAX_INGEST_PAR_MINUTE, Duration.ofMinutes(1))) {
                throw new TropDeRequetesException(
                        "Trop de requêtes d'ingestion pour cette clé. Réessayez dans une minute.");
            }
            return true;
        }
    }

    /**
     * Per IP. Keyed on the address rather than on the submitted email, because
     * the attack this exists for tries many accounts from one place — keying on
     * the email would give each guessed address its own fresh budget.
     */
    private record LimiteConnexion(LimiteurDebit limiteurDebit) implements HandlerInterceptor {

        @Override
        public boolean preHandle(HttpServletRequest requete, HttpServletResponse reponse, Object handler) {
            if (!HttpMethod.POST.matches(requete.getMethod())) {
                return true;
            }
            String cle = "login:" + requete.getRemoteAddr();
            if (!limiteurDebit.autoriser(cle, MAX_LOGIN_PAR_MINUTE, Duration.ofMinutes(1))) {
                throw new TropDeRequetesException(
                        "Trop de tentatives de connexion depuis cette adresse. Réessayez dans une minute.");
            }
            return true;
        }
    }

    /**
     * Per IP, and only on {@code POST}. Reading one's own subscriptions is
     * neither expensive nor a way to reach anybody else, so limiting it would
     * only break a bell that refreshes.
     */
    private record LimiteAbonnements(LimiteurDebit limiteurDebit) implements HandlerInterceptor {

        @Override
        public boolean preHandle(HttpServletRequest requete, HttpServletResponse reponse, Object handler) {
            if (!HttpMethod.POST.matches(requete.getMethod())) {
                return true;
            }
            String cle = "abonnements:" + requete.getRemoteAddr();
            if (!limiteurDebit.autoriser(cle, MAX_ABONNEMENTS_PAR_MINUTE, Duration.ofMinutes(1))) {
                throw new TropDeRequetesException(
                        "Trop d'abonnements créés depuis cette adresse. Réessayez dans une minute.");
            }
            return true;
        }
    }
}
