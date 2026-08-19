package tn.sncft.trino.simulateur.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tn.sncft.trino.simulateur.config.ProprietesSimulateur;
import tn.sncft.trino.simulateur.dto.CourseDuJourDTO;
import tn.sncft.trino.simulateur.dto.LotPingsDTO;
import tn.sncft.trino.simulateur.dto.PingDTO;
import tn.sncft.trino.simulateur.dto.ResultatIngestionDTO;

import java.time.Duration;
import java.util.List;

/**
 * The simulator's only contact with Trino: two HTTP calls, authenticated with
 * the ingest key. No database handle exists in this process.
 *
 * <p>Both calls swallow transport failures and return an empty result. A
 * position producer that dies because the server restarted is a producer you
 * cannot leave running during a demo.
 */
@Component
public class ClientTrino {

    private static final Logger log = LoggerFactory.getLogger(ClientTrino.class);
    private static final String ENTETE_CLE = "X-Ingest-Key";

    private final RestClient restClient;
    private final String cle;
    private final JournalLatence journalLatence;

    public ClientTrino(ProprietesSimulateur proprietes, JournalLatence journalLatence) {
        this.journalLatence = journalLatence;
        // Timeouts are not optional here. A hang is not an exception, so
        // without them a wedged API blocks the single scheduler thread
        // indefinitely and the producer stops without logging anything at all.
        ClientHttpRequestFactorySettings reglages = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(Duration.ofSeconds(5))
                .withReadTimeout(Duration.ofSeconds(10));

        this.restClient = RestClient.builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(reglages))
                .baseUrl(proprietes.getApi().getBaseUrl())
                .build();
        this.cle = proprietes.getIngestion().getCle();
        log.info("Simulateur configuré sur {}", proprietes.getApi().getBaseUrl());
    }

    /** The runs of the current service day, with their geometry and timetable. */
    public List<CourseDuJourDTO> chargerCoursesDuJour() {
        try {
            List<CourseDuJourDTO> courses = restClient.get()
                    .uri("/api/v1/ingest/courses-du-jour")
                    .header(ENTETE_CLE, cle)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<CourseDuJourDTO>>() {
                    });
            return courses == null ? List.of() : courses;
        } catch (RestClientException e) {
            log.warn("Lecture des courses du jour impossible : {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Posts a tick's positions as one batch, timing the round trip.
     *
     * <p>Only a completed call is recorded. A transport failure takes the read
     * timeout, so counting it would mean a dead API showed up in the report as a
     * ten-second ingest latency rather than as an outage.
     */
    public ResultatIngestionDTO publier(List<PingDTO> pings) {
        if (pings.isEmpty()) {
            return new ResultatIngestionDTO(0, 0);
        }
        long debut = System.nanoTime();
        try {
            ResultatIngestionDTO resultat = restClient.post()
                    .uri("/api/v1/ingest/positions")
                    .header(ENTETE_CLE, cle)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new LotPingsDTO(pings))
                    .retrieve()
                    .body(ResultatIngestionDTO.class);
            journalLatence.enregistrer((System.nanoTime() - debut) / 1_000_000L);
            return resultat == null ? new ResultatIngestionDTO(0, 0) : resultat;
        } catch (RestClientException e) {
            log.warn("Envoi de {} position(s) impossible : {}", pings.size(), e.getMessage());
            return new ResultatIngestionDTO(0, pings.size());
        }
    }
}
