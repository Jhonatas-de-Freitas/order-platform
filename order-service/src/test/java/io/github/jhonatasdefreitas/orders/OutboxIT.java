package io.github.jhonatasdefreitas.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.jhonatasdefreitas.orders.api.OrderResponse;
import io.github.jhonatasdefreitas.orders.domain.OrderStatus;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

class OutboxIT extends IntegrationTestBase {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    KafkaContainer kafka;

    @Test
    void orderIsAcceptedWithTheBrokerDownAndItsEventGoesOutWhenTheBrokerIsBack() {
        // One round trip first, so the producer already knows the topic when the broker freezes.
        OrderResponse before = placeKeyboardOrder();
        eventually().untilAsserted(() -> assertThat(pendingInOutbox(before.id())).isZero());

        OrderResponse placed;
        kafka.getDockerClient().pauseContainerCmd(kafka.getContainerId()).exec();
        try {
            placed = placeKeyboardOrder();

            // Longer than delivery.timeout.ms: the first batch is given up on and rolled back,
            // so what goes out later comes from a new poll of the table.
            await().pollDelay(Duration.ofSeconds(12)).atMost(Duration.ofSeconds(20))
                    .untilAsserted(() -> assertThat(pendingInOutbox(placed.id())).isEqualTo(1));
            assertThat(getOrder(placed.id()).status()).isEqualTo(OrderStatus.PENDING);
        } finally {
            kafka.getDockerClient().unpauseContainerCmd(kafka.getContainerId()).exec();
        }

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertThat(pendingInOutbox(placed.id())).isZero();
            assertThat(otherServices.read(OtherServices.ORDER_EVENTS, placed.id())).isNotEmpty();
        });
        // The broker may have stored the attempt that timed out, so the event can be there more than once.
        // It is always the same event: one id, which is what lets the consumers drop the copy.
        List<ConsumerRecord<String, String>> records = otherServices.read(OtherServices.ORDER_EVENTS, placed.id());
        assertThat(records).extracting(record -> JSON.readTree(record.value()).get("eventId").asString())
                .containsOnly(outboxEventId(placed.id()));

        // And the consumer side is still alive after the outage.
        otherServices.stockReserved(placed.id());
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(getOrder(placed.id()).status()).isEqualTo(OrderStatus.STOCK_RESERVED));
    }

    private String outboxEventId(UUID orderId) {
        return jdbc.sql("select event_id from outbox where message_key = ?")
                .param(orderId.toString())
                .query(UUID.class)
                .single()
                .toString();
    }
}
