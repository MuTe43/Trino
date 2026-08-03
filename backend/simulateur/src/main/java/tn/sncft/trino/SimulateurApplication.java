package tn.sncft.trino;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Placeholder entrypoint for the GPS feed producer.
 * The simulator never writes to the database; it reads courses over HTTP
 * and posts positions to the api module. Logic is added in a future phase.
 */
@SpringBootApplication
public class SimulateurApplication {

    public static void main(String[] args) {
        SpringApplication.run(SimulateurApplication.class, args);
    }
}
