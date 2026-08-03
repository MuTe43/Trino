package tn.sncft.trino;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Main entrypoint for the Trino API - the only web server in this project.
 */
@SpringBootApplication
public class TrinoApplication {

    public static void main(String[] args) {
        SpringApplication.run(TrinoApplication.class, args);
    }
}
