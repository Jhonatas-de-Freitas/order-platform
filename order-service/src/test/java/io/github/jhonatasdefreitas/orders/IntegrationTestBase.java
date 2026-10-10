package io.github.jhonatasdefreitas.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.jhonatasdefreitas.orders.api.OrderResponse;
import io.github.jhonatasdefreitas.orders.domain.OrderStatus;
import java.time.Duration;
import java.util.UUID;
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
}
