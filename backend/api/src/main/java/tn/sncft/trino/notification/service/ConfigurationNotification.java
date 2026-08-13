package tn.sncft.trino.notification.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * The executor notification work runs on, and the reason it exists.
 *
 * <p>Rule three of the phase: never block ingestion. Matching rules, resolving
 * subscribers and above all handing a message to SMTP all happen off the thread
 * that ingested the position. A hanging SMTP connection must not slow the delay
 * engine -- {@code POST /ingest/positions} answers in the same time whether the
 * mail server is up, down, or black-holing packets.
 *
 * <p>Its own pool, not the scheduler's: {@code spring.task.scheduling} is shared
 * by {@code HubSse.battementCoeur} and {@code DetecteurSilence}, and decision 8's
 * degradation safety net must not queue behind a mail server either.
 */
@Configuration
@EnableAsync
public class ConfigurationNotification {

    private static final Logger log = LoggerFactory.getLogger(ConfigurationNotification.class);

    public static final String EXECUTEUR = "executeurNotifications";

    @Bean(EXECUTEUR)
    public Executor executeurNotifications() {
        ThreadPoolTaskExecutor executeur = new ThreadPoolTaskExecutor();
        executeur.setCorePoolSize(2);
        executeur.setMaxPoolSize(4);
        // Bounded. An unbounded queue turns a dead mail server into a heap
        // problem: at x20 replay the engine can offer work far faster than SMTP
        // times out, and the backlog would grow until the process died.
        executeur.setQueueCapacity(1_000);
        executeur.setThreadNamePrefix("notif-");
        // Dropping beats blocking. The default policy throws in the caller, and
        // the caller here is the after-commit callback on the ingestion thread --
        // which is exactly the thread this whole class exists to protect. A lost
        // notification under a backlog of a thousand is the acceptable failure;
        // a stalled position feed is not.
        executeur.setRejectedExecutionHandler((tache, pool) ->
                log.warn("Notification abandonnée : file d'attente saturée ({} en attente).",
                        pool.getQueue().size()));
        // Let in-flight dispatches finish on shutdown rather than leaving rows
        // stuck at EN_ATTENTE with no record of why.
        executeur.setWaitForTasksToCompleteOnShutdown(true);
        executeur.setAwaitTerminationSeconds(10);
        executeur.initialize();
        return executeur;
    }
}
