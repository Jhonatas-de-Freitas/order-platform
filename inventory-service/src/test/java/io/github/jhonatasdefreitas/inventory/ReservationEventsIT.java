package io.github.jhonatasdefreitas.inventory;

import static io.github.jhonatasdefreitas.inventory.OtherServices.item;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.jhonatasdefreitas.inventory.api.ProductResponse;
import io.github.jhonatasdefreitas.inventory.api.ReservationResponse;
import io.github.jhonatasdefreitas.inventory.domain.ReservationStatus;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class ReservationEventsIT extends IntegrationTestBase {

    @Test
    void reservesTheStockAtTheCatalogPriceAndTellsWhatWasReserved() {
        ProductResponse keyboard = createProduct("Teclado Mecânico", "349.90", 50);
        UUID orderId = UUID.randomUUID();

        otherServices.orderCreated(orderId, item(keyboard.id(), 2));

        ConsumerRecord<String, String> record = awaitPublished(orderId, "StockReserved");
        assertThat(record.key()).isEqualTo(orderId.toString());
        assertThat(new String(record.headers().lastHeader("event-type").value(), StandardCharsets.UTF_8))
                .isEqualTo("StockReserved");
        // The raw text, to be sure money leaves with two decimal places.
        assertThat(record.value()).contains("\"unitPrice\":349.90").contains("\"totalAmount\":699.80");
        JsonNode event = JSON.readTree(record.value());
        assertThat(UUID.fromString(event.get("eventId").asString())).isNotNull();
        assertThat(event.get("type").asString()).isEqualTo("StockReserved");
        assertThat(event.get("orderId").asString()).isEqualTo(orderId.toString());
        assertThat(event.get("payload")).isEqualTo(JSON.readTree("""
                {"items":[{"productId":"%s","productName":"Teclado Mecânico","quantity":2,"unitPrice":349.90}],
                 "totalAmount":699.80}
                """.formatted(keyboard.id())));

        assertThat(stockOf(keyboard.id())).isEqualTo(48);
        ReservationResponse reservation = getReservation(orderId);
        assertThat(reservation.orderId()).isEqualTo(orderId);
        assertThat(reservation.status()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(reservation.items()).containsExactly(
                new ReservationResponse.Item(keyboard.id(), 2, new BigDecimal("349.90")));
        assertThat(reservation.releasedAt()).isNull();
        assertThat(event.get("occurredAt").asString()).isEqualTo(reservation.createdAt().toString());
    }

    @Test
    void totalAddsUpEveryItem() {
        ProductResponse keyboard = createProduct("Teclado Mecânico", "349.90", 50);
        ProductResponse mouse = createProduct("Mouse Sem Fio", "129.90", 80);
        UUID orderId = UUID.randomUUID();

        otherServices.orderCreated(orderId, item(mouse.id(), 3), item(keyboard.id(), 1));

        JsonNode payload = payloadOf(awaitPublished(orderId, "StockReserved"));
        assertThat(payload.get("totalAmount").decimalValue()).isEqualByComparingTo("739.60");
        assertThat(payload.get("items")).hasSize(2);
        assertThat(payload.get("items").get(0).get("productName").asString()).isEqualTo("Mouse Sem Fio");
        assertThat(stockOf(mouse.id())).isEqualTo(77);
        assertThat(stockOf(keyboard.id())).isEqualTo(49);
    }

    @Test
    void rejectsWhenThereIsNoStock() {
        ProductResponse headset = createProduct("Headset USB", "279.50", 0);
        UUID orderId = UUID.randomUUID();

        otherServices.orderCreated(orderId, item(headset.id(), 1));

        ConsumerRecord<String, String> record = awaitPublished(orderId, "StockRejected");
        assertThat(new String(record.headers().lastHeader("event-type").value(), StandardCharsets.UTF_8))
                .isEqualTo("StockRejected");
        assertThat(payloadOf(record)).isEqualTo(JSON.readTree("""
                {"reason":"INSUFFICIENT_STOCK",
                 "unavailableItems":[{"productId":"%s","requestedQuantity":1,"availableQuantity":0}]}
                """.formatted(headset.id())));
        assertThat(Instant.parse(JSON.readTree(record.value()).get("occurredAt").asString())).isNotNull();
        assertThat(stockOf(headset.id())).isZero();
        assertNoReservation(orderId);
    }

    @Test
    void reservesNothingWhenOneOfTwoItemsIsShort() {
        ProductResponse keyboard = createProduct("Teclado Mecânico", "349.90", 50);
        ProductResponse headset = createProduct("Headset USB", "279.50", 2);
        UUID orderId = UUID.randomUUID();

        otherServices.orderCreated(orderId, item(keyboard.id(), 2), item(headset.id(), 3));

        assertThat(payloadOf(awaitPublished(orderId, "StockRejected"))).isEqualTo(JSON.readTree("""
                {"reason":"INSUFFICIENT_STOCK",
                 "unavailableItems":[{"productId":"%s","requestedQuantity":3,"availableQuantity":2}]}
                """.formatted(headset.id())));
        assertThat(stockOf(keyboard.id())).isEqualTo(50);
        assertThat(stockOf(headset.id())).isEqualTo(2);
        assertNoReservation(orderId);
        assertThat(outboxCount(orderId, "StockReserved")).isZero();
    }

    @Test
    void rejectsAnOrderWithAProductThatDoesNotExist() {
        ProductResponse keyboard = createProduct("Teclado Mecânico", "349.90", 50);
        UUID unknown = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        otherServices.orderCreated(orderId, item(keyboard.id(), 2), item(unknown, 1));

        assertThat(payloadOf(awaitPublished(orderId, "StockRejected"))).isEqualTo(JSON.readTree("""
                {"reason":"PRODUCT_NOT_FOUND",
                 "unavailableItems":[{"productId":"%s","requestedQuantity":1,"availableQuantity":0}]}
                """.formatted(unknown)));
        assertThat(stockOf(keyboard.id())).isEqualTo(50);
        assertNoReservation(orderId);
    }

    @Test
    void sameOrderCreatedDeliveredTwiceReservesOnce() {
        ProductResponse keyboard = createProduct("Teclado Mecânico", "349.90", 50);
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        String payload = OtherServices.orderCreatedPayload(item(keyboard.id(), 2));

        otherServices.publish(OtherServices.ORDER_EVENTS, eventId, "OrderCreated", orderId, payload);
        otherServices.publish(OtherServices.ORDER_EVENTS, eventId, "OrderCreated", orderId, payload);
        awaitEverythingConsumed(orderId);

        assertThat(stockOf(keyboard.id())).isEqualTo(48);
        assertThat(outboxCount(orderId, "StockReserved")).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from processed_events where event_id = ?")
                .param(eventId).query(Long.class).single()).isEqualTo(1);
        eventually().untilAsserted(() -> assertThat(otherServices.read(OtherServices.INVENTORY_EVENTS, orderId))
                .extracting(IntegrationTestBase::typeOf)
                .containsExactly("StockReserved"));
    }

    @Test
    void sameOrderInADifferentEventDoesNotReserveAgain() {
        ProductResponse keyboard = createProduct("Teclado Mecânico", "349.90", 50);
        UUID orderId = UUID.randomUUID();

        otherServices.orderCreated(orderId, item(keyboard.id(), 2));
        awaitProcessed(otherServices.orderCreated(orderId, item(keyboard.id(), 2)));

        assertThat(stockOf(keyboard.id())).isEqualTo(48);
        assertThat(outboxCount(orderId, "StockReserved")).isEqualTo(1);
    }

    @Test
    void cancelledOrderGivesItsStockBack() {
        ProductResponse notebook = createProduct("Notebook Pro 16", "7499.00", 10);
        ProductResponse mouse = createProduct("Mouse Sem Fio", "129.90", 80);
        UUID orderId = UUID.randomUUID();
        otherServices.orderCreated(orderId, item(notebook.id(), 1), item(mouse.id(), 2));
        awaitPublished(orderId, "StockReserved");
        assertThat(stockOf(notebook.id())).isEqualTo(9);

        otherServices.orderCancelled(orderId);

        ConsumerRecord<String, String> record = awaitPublished(orderId, "StockReleased");
        assertThat(new String(record.headers().lastHeader("event-type").value(), StandardCharsets.UTF_8))
                .isEqualTo("StockReleased");
        assertThat(payloadOf(record)).isEqualTo(JSON.readTree("""
                {"items":[{"productId":"%s","quantity":1},{"productId":"%s","quantity":2}]}
                """.formatted(notebook.id(), mouse.id())));
        assertThat(stockOf(notebook.id())).isEqualTo(10);
        assertThat(stockOf(mouse.id())).isEqualTo(80);
        ReservationResponse reservation = getReservation(orderId);
        assertThat(reservation.status()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(reservation.releasedAt()).isAfterOrEqualTo(reservation.createdAt());
        assertThat(reservation.items()).hasSize(2);
        assertThat(JSON.readTree(record.value()).get("occurredAt").asString())
                .isEqualTo(reservation.releasedAt().toString());
    }

    @Test
    void orderCancelledTwiceGivesTheStockBackOnce() {
        ProductResponse notebook = createProduct("Notebook Pro 16", "7499.00", 10);
        UUID orderId = UUID.randomUUID();
        otherServices.orderCreated(orderId, item(notebook.id(), 1));
        awaitPublished(orderId, "StockReserved");

        otherServices.orderCancelled(orderId);
        awaitProcessed(otherServices.orderCancelled(orderId));

        assertThat(stockOf(notebook.id())).isEqualTo(10);
        assertThat(outboxCount(orderId, "StockReleased")).isEqualTo(1);
    }

    @Test
    void cancellationOfAnOrderThatReservedNothingChangesNothing() {
        ProductResponse headset = createProduct("Headset USB", "279.50", 0);
        UUID orderId = UUID.randomUUID();
        otherServices.orderCreated(orderId, item(headset.id(), 1));
        awaitPublished(orderId, "StockRejected");

        awaitProcessed(otherServices.publish(OtherServices.ORDER_EVENTS, "OrderCancelled", orderId, """
                {"customerId":"c-1001","customerName":"Ana Souza","customerEmail":"ana@exemplo.com",
                 "reason":"OUT_OF_STOCK","detail":"Headset USB: pedido 1, disponível 0"}
                """));

        assertThat(stockOf(headset.id())).isZero();
        assertThat(outboxCount(orderId, "StockReleased")).isZero();
        assertThat(otherServices.read(OtherServices.DEAD_LETTERS, orderId)).isEmpty();
    }

    @Test
    void eventOfAnotherTypeIsIgnoredWithoutBreaking() {
        ProductResponse keyboard = createProduct("Teclado Mecânico", "349.90", 50);
        UUID orderId = UUID.randomUUID();
        otherServices.orderCreated(orderId, item(keyboard.id(), 2));
        awaitPublished(orderId, "StockReserved");

        awaitProcessed(otherServices.publish(OtherServices.ORDER_EVENTS, "OrderConfirmed", orderId, """
                {"customerId":"c-1001","customerName":"Ana Souza","customerEmail":"ana@exemplo.com",
                 "totalAmount":699.80,
                 "items":[{"productId":"%s","productName":"Teclado Mecânico","quantity":2,"unitPrice":349.90}]}
                """.formatted(keyboard.id())));

        assertThat(stockOf(keyboard.id())).isEqualTo(48);
        assertThat(getReservation(orderId).status()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(otherServices.read(OtherServices.DEAD_LETTERS, orderId)).isEmpty();
    }

    @Test
    void countsReservationsByOutcome() {
        // The counters are shared by every test of this context, so only the difference is checked.
        String reserved = "stock_reservations_total{outcome=\"reserved\"}";
        String rejected = "stock_reservations_total{outcome=\"rejected\"}";
        String released = "stock_reservations_total{outcome=\"released\"}";
        double reservedBefore = metric(reserved);
        double rejectedBefore = metric(rejected);
        double releasedBefore = metric(released);
        ProductResponse headset = createProduct("Headset USB", "279.50", 1);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        otherServices.orderCreated(first, item(headset.id(), 1));
        awaitPublished(first, "StockReserved");
        otherServices.orderCreated(second, item(headset.id(), 1));
        awaitPublished(second, "StockRejected");
        otherServices.orderCancelled(first);
        awaitPublished(first, "StockReleased");

        // Counted after the commit, so a moment after the event is written.
        eventually().untilAsserted(() -> {
            assertThat(metric(reserved)).isGreaterThanOrEqualTo(reservedBefore + 1);
            assertThat(metric(rejected)).isGreaterThanOrEqualTo(rejectedBefore + 1);
            assertThat(metric(released)).isGreaterThanOrEqualTo(releasedBefore + 1);
        });
    }

    // An event of a type the service ignores, sent after everything else of this order.
    // Once it has been gone through, so has everything that was ahead of it on the same partition.
    private void awaitEverythingConsumed(UUID orderId) {
        awaitProcessed(otherServices.publish(OtherServices.ORDER_EVENTS, "TestMarker", orderId, "{}"));
    }
}
