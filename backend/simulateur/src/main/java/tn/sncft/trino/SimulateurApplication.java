package tn.sncft.trino;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entrypoint for the GPS feed producer.
 *
 * <p>This process is a stand-in for real AVL hardware and must stay swappable:
 * it never touches the database. It reads the day's courses over HTTP and
 * POSTs positions back to the api module, authenticating with an ingest key
 * and nothing else.
 *
 * <p>Runs headless -- the api module is the only web server in this project.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class SimulateurApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(SimulateurApplication.class)
                .web(WebApplicationType.NONE)
                .run(args);
    }
}
