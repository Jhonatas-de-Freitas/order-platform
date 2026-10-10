package io.github.jhonatasdefreitas.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.jhonatasdefreitas.orders.api.OrderResponse;
import io.github.jhonatasdefreitas.orders.domain.OrderStatus;
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
 * The whole service against a real PostgreSQL and a real Kafka. The other two services are not here:
 * {@link OtherServices} publishes their events. Every test creates its own order, so they can share
 * the database and the topics without stepping on each other.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "app.stream.keep-alive=300ms")
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

    protected OrderResponse placeOrder(String customerId, String productId, int quantity) {
        return client.post().uri("/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"customerId":"%s","customerName":"Ana Souza","customerEmail":"ana@exemplo.com",
                         "items":[{"productId":"%s","quantity":%d}]}
                        """.formatted(customerId, productId, quantity))
                .exchange()
                .expectStatus().isAccepted()
                .expectBody(OrderResponse.class)
                .returnResult().getResponseBody();
    }

    protected OrderResponse placeKeyboardOrder() {
        return placeOrder("c-1001", OtherServices.KEYBOARD, 2);
    }

    protected OrderResponse getOrder(UUID orderId) {
        return client.get().uri("/orders/{id}", orderId)
                .exchange()
                .expectStatus().isOk()
                .expectBody(OrderResponse.class)
                .returnResult().getResponseBody();
    }

    protected OrderResponse awaitStatus(UUID orderId, OrderStatus status) {
        eventually().untilAsserted(() -> assertThat(getOrder(orderId).status()).isEqualTo(status));
        return getOrder(orderId);
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
        return otherServices.read(OtherServices.ORDER_EVENTS, orderId).stream()
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
     * {@code dead_letter_messages_total}, or with labels, {@code orders_status_changes_total{status="CONFIRMED"}}.
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
