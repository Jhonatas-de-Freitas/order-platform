package io.github.jhonatasdefreitas.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.jhonatasdefreitas.inventory.api.ProductResponse;
import io.github.jhonatasdefreitas.inventory.api.ReservationResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.awaitility.core.ConditionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The whole service against a real PostgreSQL and a real Kafka. The order service is not here:
 * {@link OtherServices} publishes its events. Every test creates its own products and its own orders,
 * so they can share the database and the topics without stepping on each other.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import({TestcontainersConfiguration.class, OtherServices.class})
@Testcontainers(disabledWithoutDocker = true)
abstract class IntegrationTestBase {

    protected static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    protected RestTestClient client;

    @Autowired
    protected OtherServices otherServices;

    @Autowired
    protected JdbcClient jdbc;

    protected static ConditionFactory eventually() {
        return await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100));
    }

    protected ProductResponse createProduct(String name, String price, int availableQuantity) {
        return client.post().uri("/products")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"name":"%s","description":"Criado por um teste","price":%s,"availableQuantity":%d}
                        """.formatted(name, price, availableQuantity))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(ProductResponse.class)
                .returnResult().getResponseBody();
    }

    protected int stockOf(UUID productId) {
        return client.get().uri("/products/{id}", productId)
                .exchange()
                .expectStatus().isOk()
                .expectBody(ProductResponse.class)
                .returnResult().getResponseBody().availableQuantity();
    }

    protected ReservationResponse getReservation(UUID orderId) {
        return client.get().uri("/reservations/{orderId}", orderId)
                .exchange()
                .expectStatus().isOk()
                .expectBody(ReservationResponse.class)
                .returnResult().getResponseBody();
    }

    protected void assertNoReservation(UUID orderId) {
        client.get().uri("/reservations/{orderId}", orderId)
                .exchange()
                .expectStatus().isNotFound();
    }

    /**
     * Waits until the consumer has gone through an event. Used after an event that is expected to change
     * nothing, where there is no new state to wait for.
     */
    protected void awaitProcessed(UUID eventId) {
        eventually().until(() -> jdbc.sql("select count(*) from processed_events where event_id = ?")
                .param(eventId)
                .query(Long.class)
                .single() == 1);
    }

    protected long pendingInOutbox(UUID orderId) {
        return jdbc.sql("select count(*) from outbox where message_key = ? and published_at is null")
                .param(orderId.toString())
                .query(Long.class)
                .single();
    }

    protected long outboxCount(UUID orderId, String eventType) {
        return jdbc.sql("select count(*) from outbox where message_key = ? and event_type = ?")
                .params(orderId.toString(), eventType)
                .query(Long.class)
                .single();
    }

    /** Waits for the one event of that type this service has published for the order. */
    protected ConsumerRecord<String, String> awaitPublished(UUID orderId, String type) {
        eventually().untilAsserted(() -> assertThat(published(orderId, type)).hasSize(1));
        return published(orderId, type).getFirst();
    }

    protected List<ConsumerRecord<String, String>> published(UUID orderId, String type) {
        return otherServices.read(OtherServices.INVENTORY_EVENTS, orderId).stream()
                .filter(record -> typeOf(record).equals(type))
                .toList();
    }

    protected static String typeOf(ConsumerRecord<String, String> record) {
        return JSON.readTree(record.value()).get("type").asString();
    }

    protected static JsonNode payloadOf(ConsumerRecord<String, String> record) {
        return JSON.readTree(record.value()).get("payload");
    }

    /**
     * Sum of the samples of a series in /actuator/prometheus, as Prometheus names it:
     * {@code dead_letter_messages_total}, or with labels, {@code stock_reservations_total{outcome="reserved"}}.
     */
    protected double metric(String series) {
        String body = client.get().uri("/actuator/prometheus")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult().getResponseBody();
        return body.lines()
                .filter(line -> line.startsWith(series + " ") || line.startsWith(series + "{"))
                .mapToDouble(line -> Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1)))
                .sum();
    }
}
