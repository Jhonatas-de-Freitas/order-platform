package io.github.jhonatasdefreitas.inventory;

import static io.github.jhonatasdefreitas.inventory.OtherServices.item;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.jhonatasdefreitas.inventory.api.ProductResponse;
import io.github.jhonatasdefreitas.inventory.domain.RequestedItem;
import io.github.jhonatasdefreitas.inventory.domain.ReservationService;
import io.github.jhonatasdefreitas.inventory.domain.ReservationStatus;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.kafka.KafkaContainer;

class OutboxIT extends IntegrationTestBase {

    @Autowired
    KafkaContainer kafka;

    @Autowired
    ReservationService reservations;

    @Test
    void stockIsReservedWithTheBrokerDownAndItsEventGoesOutWhenTheBrokerIsBack() {
        ProductResponse keyboard = createProduct("Teclado Mecânico", "349.90", 50);
        // One round trip first, so the producer already knows the topic when the broker freezes.
        UUID before = UUID.randomUUID();
        otherServices.orderCreated(before, item(keyboard.id(), 1));
        awaitPublished(before, "StockReserved");
        eventually().untilAsserted(() -> assertThat(pendingInOutbox(before)).isZero());

        UUID orderId = UUID.randomUUID();
        kafka.getDockerClient().pauseContainerCmd(kafka.getContainerId()).exec();
        try {
            // Nothing can be consumed now, so the order comes in the way the consumer would bring it.
            reservations.reserve(orderId, List.of(new RequestedItem(keyboard.id(), 2)));

            // Longer than delivery.timeout.ms: the first batch is given up on and rolled back,
            // so what goes out later comes from a new poll of the table.
            await().pollDelay(Duration.ofSeconds(12)).atMost(Duration.ofSeconds(20))
                    .untilAsserted(() -> assertThat(pendingInOutbox(orderId)).isEqualTo(1));
            // The gauge counts the table on every scrape, so it sees the event that is stuck.
            assertThat(metric("outbox_pending_events")).isGreaterThanOrEqualTo(1);
            assertThat(getReservation(orderId).status()).isEqualTo(ReservationStatus.RESERVED);
            assertThat(stockOf(keyboard.id())).isEqualTo(47);
        } finally {
            kafka.getDockerClient().unpauseContainerCmd(kafka.getContainerId()).exec();
        }

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertThat(pendingInOutbox(orderId)).isZero();
            assertThat(otherServices.read(OtherServices.INVENTORY_EVENTS, orderId)).isNotEmpty();
        });
        // The broker may have stored the attempt that timed out, so the event can be there more than once.
        // It is always the same event: one id, which is what lets the consumers drop the copy.
        List<ConsumerRecord<String, String>> records = otherServices.read(OtherServices.INVENTORY_EVENTS, orderId);
        assertThat(records).extracting(record -> JSON.readTree(record.value()).get("eventId").asString())
                .containsOnly(outboxEventId(orderId));

        // And the consumer side is still alive after the outage.
        UUID after = UUID.randomUUID();
        otherServices.orderCreated(after, item(keyboard.id(), 1));
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(outboxCount(after, "StockReserved")).isEqualTo(1));
    }

    private String outboxEventId(UUID orderId) {
        return jdbc.sql("select event_id from outbox where message_key = ?")
                .param(orderId.toString())
                .query(UUID.class)
                .single()
                .toString();
    }
}
