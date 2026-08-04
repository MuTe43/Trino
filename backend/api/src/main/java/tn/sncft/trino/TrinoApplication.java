package tn.sncft.trino;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Main entrypoint for the Trino API - the only web server in this project.
 * Scheduling is enabled for the daily timetable generation (GenerateurCourses).
 */
@SpringBootApplication
@EnableScheduling
public class TrinoApplication {

    public static void main(String[] args) {
        SpringApplication.run(TrinoApplication.class, args);
    }
}
