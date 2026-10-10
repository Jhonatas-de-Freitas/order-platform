package io.github.jhonatasdefreitas.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.jhonatasdefreitas.payments.api.PaymentResponse;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The whole service against a real PostgreSQL and a real Kafka. The inventory service is not here:
 * {@link OtherServices} publishes its events. Every test uses its own order, so they can share
 * the database and the topics without stepping on each other.
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

    protected PaymentResponse getPayment(UUID orderId) {
        return client.get().uri("/payments/{orderId}", orderId)
                .exchange()
                .expectStatus().isOk()
                .expectBody(PaymentResponse.class)
                .returnResult().getResponseBody();
    }

    protected void assertNoPayment(UUID orderId) {
        client.get().uri("/payments/{orderId}", orderId)
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

    protected long outboxCount(UUID orderId) {
        return jdbc.sql("select count(*) from outbox where message_key = ?")
                .param(orderId.toString())
                .query(Long.class)
                .single();
    }

    /** Waits for the one event of that type this service has published for the order. */
    protected ConsumerRecord<String, String> awaitPublished(UUID orderId, String type) {
        eventually().untilAsserted(() -> assertThat(published(orderId, type)).hasSize(1));
        return published(orderId, type).getFirst();
    }

    protected List<ConsumerRecord<String, String>> published(UUID orderId, String type) {
        return otherServices.read(OtherServices.PAYMENT_EVENTS, orderId).stream()
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
     * {@code dead_letter_messages_total}, or with labels, {@code payments_total{outcome="approved"}}.
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
