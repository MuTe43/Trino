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
 * <p>Today that is one thing: the rate limit on {@code POST /abonnements}. It
 * lives in an interceptor rather than in the controller because a controller
 * holds no logic (invariant 7), and rather than in the service because a service
 * has no business knowing a caller's IP address.
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
