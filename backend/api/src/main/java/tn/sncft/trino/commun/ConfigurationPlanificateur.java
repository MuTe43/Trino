package tn.sncft.trino.commun;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.task.ThreadPoolTaskSchedulerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.support.TaskUtils;
import org.springframework.util.ErrorHandler;

import java.io.IOException;

/**
 * Installs a custom {@link ErrorHandler} on the application's scheduled-task
 * executor (used by both {@link tn.sncft.trino.diffusion.HubSse#battementCoeur}
 * and {@link tn.sncft.trino.circulation.service.DetecteurSilence}).
 *
 * <p>Without this, a failed write from the SSE heartbeat unwinds through
 * {@code DelegatingErrorHandlingRunnable}, which logs any exception at ERROR
 * with a full stack trace -- including a client that simply disconnected,
 * which on Windows surfaces as a bare {@link IOException} rather than the
 * more specific {@code AsyncRequestNotUsableException} / {@code
 * ClientAbortException} that {@link tn.sncft.trino.diffusion.HubSse#envoyer}
 * already handles around its own explicit {@code send()}. This is the second,
 * separate leak: the heartbeat's failure never reaches that method at all.
 *
 * <p>Registered as a {@link ThreadPoolTaskSchedulerCustomizer} rather than as
 * a hand-built {@code TaskScheduler} bean so Boot's autoconfigured scheduler
 * -- including the {@code spring.task.scheduling.pool.size: 4} binding set in
 * phase 3 -- is left in place; this only swaps its error handler.
 *
 * <p>Deliberately scoped to {@link IOException}: any exception thrown by
 * {@link tn.sncft.trino.circulation.service.DetecteurSilence}'s DB sweep is a
 * {@code DataAccessException} or similar, never an {@code IOException}, so
 * routing only I/O failures to DEBUG cannot hide a broken silence-detection
 * safety net -- everything else still logs at ERROR exactly as Boot's default
 * handler would.
 */
@Configuration
public class ConfigurationPlanificateur {

    private static final Logger log = LoggerFactory.getLogger(ConfigurationPlanificateur.class);

    @Bean
    public ThreadPoolTaskSchedulerCustomizer personnalisationPlanificateur() {
        return scheduler -> scheduler.setErrorHandler(erreur -> {
            if (erreur instanceof IOException) {
                // A client that vanished mid heartbeat -- normal traffic for
                // an SSE stream, not an incident.
                log.debug("Tâche planifiée interrompue par une déconnexion client : {}", erreur.getMessage());
                return;
            }
            // Same behaviour as Spring's own default handler
            // (TaskUtils.LOG_AND_SUPPRESS_ERROR_HANDLER): log at ERROR and
            // swallow, so one failing run of a @Scheduled method never stops
            // the executor from scheduling the next one.
            TaskUtils.LOG_AND_SUPPRESS_ERROR_HANDLER.handleError(erreur);
        });
    }
}
