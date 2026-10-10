package io.github.jhonatasdefreitas.orders;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jhonatasdefreitas.orders.api.OrderResponse;
import io.github.jhonatasdefreitas.orders.api.OrderResponse.HistoryEntry;
import io.github.jhonatasdefreitas.orders.domain.CancellationReason;
import io.github.jhonatasdefreitas.orders.domain.OrderStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class OrderEventsIT extends IntegrationTestBase {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void confirmsAfterStockIsReservedAndPaymentApproved() {
        UUID orderId = placeKeyboardOrder().id();

        otherServices.stockReserved(orderId);

        OrderResponse reserved = awaitStatus(orderId, OrderStatus.STOCK_RESERVED);
        assertThat(reserved.totalAmount()).isEqualTo(new BigDecimal("699.80"));
        assertThat(reserved.items()).containsExactly(new OrderResponse.Item(
                UUID.fromString(OtherServices.KEYBOARD), "Teclado Mecânico", 2, new BigDecimal("349.90")));

        otherServices.paymentApproved(orderId);

        OrderResponse confirmed = awaitStatus(orderId, OrderStatus.CONFIRMED);
        assertThat(confirmed.cancellationReason()).isNull();
        assertThat(confirmed.history()).extracting(HistoryEntry::status)
                .containsExactly(OrderStatus.PENDING, OrderStatus.STOCK_RESERVED, OrderStatus.CONFIRMED);
        assertThat(confirmed.updatedAt()).isAfterOrEqualTo(confirmed.createdAt());

        ConsumerRecord<String, String> record = awaitPublished(orderId, "OrderConfirmed");
        // The raw text, to be sure money leaves with two decimal places.
        assertThat(record.value()).contains("\"totalAmount\":699.80").contains("\"unitPrice\":349.90");
        assertThat(JSON.readTree(record.value()).get("payload")).isEqualTo(JSON.readTree("""
                {"customerId":"c-1001","customerName":"Ana Souza","customerEmail":"ana@exemplo.com",
                 "totalAmount":699.80,
                 "items":[{"productId":"%s","productName":"Teclado Mecânico","quantity":2,"unitPrice":349.90}]}
                """.formatted(OtherServices.KEYBOARD)));
    }

    @Test
    void cancelsWhenThereIsNoStock() {
        UUID orderId = placeOrder("c-1001", OtherServices.HEADSET, 1).id();

        otherServices.stockRejected(orderId);

        OrderResponse cancelled = awaitStatus(orderId, OrderStatus.CANCELLED);
        String detail = OtherServices.HEADSET + ": pedido 1, disponível 0";
        assertThat(cancelled.cancellationReason()).isEqualTo(CancellationReason.OUT_OF_STOCK);
        assertThat(cancelled.cancellationDetail()).isEqualTo(detail);
        assertThat(cancelled.totalAmount()).isNull();
        assertThat(cancelled.history()).extracting(HistoryEntry::status)
                .containsExactly(OrderStatus.PENDING, OrderStatus.CANCELLED);

        JsonNode event = JSON.readTree(awaitPublished(orderId, "OrderCancelled").value());
        assertThat(event.get("payload")).isEqualTo(JSON.createObjectNode()
                .put("customerId", "c-1001")
                .put("customerName", "Ana Souza")
                .put("customerEmail", "ana@exemplo.com")
                .put("reason", "OUT_OF_STOCK")
                .put("detail", detail));
    }

    @Test
    void cancelsWhenPaymentIsRefused() {
        UUID orderId = placeKeyboardOrder().id();
        otherServices.stockReserved(orderId);
        awaitStatus(orderId, OrderStatus.STOCK_RESERVED);

        otherServices.paymentRefused(orderId);

        OrderResponse cancelled = awaitStatus(orderId, OrderStatus.CANCELLED);
        assertThat(cancelled.cancellationReason()).isEqualTo(CancellationReason.PAYMENT_REFUSED);
        assertThat(cancelled.cancellationDetail()).isEqualTo("Pagamento recusado: LIMIT_EXCEEDED");
        assertThat(cancelled.history()).extracting(HistoryEntry::status)
                .containsExactly(OrderStatus.PENDING, OrderStatus.STOCK_RESERVED, OrderStatus.CANCELLED);

        JsonNode payload = JSON.readTree(awaitPublished(orderId, "OrderCancelled").value()).get("payload");
        assertThat(payload.get("reason").asString()).isEqualTo("PAYMENT_REFUSED");
        assertThat(payload.get("detail").asString()).isEqualTo("Pagamento recusado: LIMIT_EXCEEDED");
    }

    @Test
    void sameEventDeliveredTwiceIsAppliedOnce() {
        UUID orderId = placeKeyboardOrder().id();
        UUID stockEvent = UUID.randomUUID();
        UUID paymentEvent = UUID.randomUUID();
        String reserved = """
                {"items":[{"productId":"%s","productName":"Teclado Mecânico","quantity":2,"unitPrice":349.90}],
                 "totalAmount":699.80}
                """.formatted(OtherServices.KEYBOARD);
        String approved = """
                {"paymentId":"a3c1","amount":699.80}
                """;

        otherServices.publish(OtherServices.INVENTORY_EVENTS, stockEvent, "StockReserved", orderId, reserved);
        otherServices.publish(OtherServices.INVENTORY_EVENTS, stockEvent, "StockReserved", orderId, reserved);
        awaitStatus(orderId, OrderStatus.STOCK_RESERVED);
        otherServices.publish(OtherServices.PAYMENT_EVENTS, paymentEvent, "PaymentApproved", orderId, approved);
        otherServices.publish(OtherServices.PAYMENT_EVENTS, paymentEvent, "PaymentApproved", orderId, approved);
        awaitStatus(orderId, OrderStatus.CONFIRMED);
        awaitEverythingConsumed(orderId);

        assertThat(getOrder(orderId).history()).extracting(HistoryEntry::status)
                .containsExactly(OrderStatus.PENDING, OrderStatus.STOCK_RESERVED, OrderStatus.CONFIRMED);
        assertThat(outboxCount(orderId, "OrderConfirmed")).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from processed_events where event_id in (?, ?)")
                .params(stockEvent, paymentEvent).query(Long.class).single()).isEqualTo(2);
        eventually().untilAsserted(() -> assertThat(otherServices.read(OtherServices.ORDER_EVENTS, orderId))
                .extracting(record -> JSON.readTree(record.value()).get("type").asString())
                .containsExactly("OrderCreated", "OrderConfirmed"));
    }

    @Test
    void paymentApprovedBeforeStockReservedStillConfirms() {
        UUID orderId = placeKeyboardOrder().id();

        UUID approval = otherServices.paymentApproved(orderId);
        awaitProcessed(approval);

        OrderResponse waiting = getOrder(orderId);
        assertThat(waiting.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(waiting.history()).hasSize(1);
        assertThat(outboxCount(orderId, "OrderConfirmed")).isZero();

        otherServices.stockReserved(orderId);

        OrderResponse confirmed = awaitStatus(orderId, OrderStatus.CONFIRMED);
        assertThat(confirmed.totalAmount()).isEqualTo(new BigDecimal("699.80"));
        assertThat(confirmed.history()).extracting(HistoryEntry::status)
                .containsExactly(OrderStatus.PENDING, OrderStatus.STOCK_RESERVED, OrderStatus.CONFIRMED);

        JsonNode payload = JSON.readTree(awaitPublished(orderId, "OrderConfirmed").value()).get("payload");
        assertThat(payload.get("totalAmount").decimalValue()).isEqualByComparingTo("699.80");
        assertThat(payload.get("items").get(0).get("productName").asString()).isEqualTo("Teclado Mecânico");
    }

    @Test
    void paymentRefusedBeforeStockReservedCancelsAndTheLateStockEventIsIgnored() {
        UUID orderId = placeKeyboardOrder().id();

        otherServices.paymentRefused(orderId);
        awaitStatus(orderId, OrderStatus.CANCELLED);
        awaitProcessed(otherServices.stockReserved(orderId));

        OrderResponse cancelled = getOrder(orderId);
        assertThat(cancelled.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelled.cancellationReason()).isEqualTo(CancellationReason.PAYMENT_REFUSED);
        assertThat(cancelled.totalAmount()).isNull();
        assertThat(cancelled.history()).extracting(HistoryEntry::status)
                .containsExactly(OrderStatus.PENDING, OrderStatus.CANCELLED);
        // The inventory did reserve the stock. OrderCancelled is what tells it to give the stock back.
        assertThat(outboxCount(orderId, "OrderCancelled")).isEqualTo(1);
    }

    @Test
    void confirmedOrderIgnoresEventsThatArriveLater() {
        UUID orderId = placeKeyboardOrder().id();
        otherServices.stockReserved(orderId);
        otherServices.paymentApproved(orderId);
        OrderResponse confirmed = awaitStatus(orderId, OrderStatus.CONFIRMED);

        awaitProcessed(otherServices.paymentRefused(orderId));
        awaitProcessed(otherServices.stockRejected(orderId));

        assertThat(getOrder(orderId)).isEqualTo(confirmed);
        assertThat(outboxCount(orderId, "OrderCancelled")).isZero();
    }

    @Test
    void cancelledOrderIgnoresEventsThatArriveLater() {
        UUID orderId = placeOrder("c-1001", OtherServices.HEADSET, 1).id();
        otherServices.stockRejected(orderId);
        OrderResponse cancelled = awaitStatus(orderId, OrderStatus.CANCELLED);

        awaitProcessed(otherServices.stockReserved(orderId));
        awaitProcessed(otherServices.paymentApproved(orderId));

        assertThat(getOrder(orderId)).isEqualTo(cancelled);
        assertThat(outboxCount(orderId, "OrderConfirmed")).isZero();
        assertThat(outboxCount(orderId, "OrderCancelled")).isEqualTo(1);
    }

    @Test
    void eventOfUnknownTypeIsIgnoredWithoutBreaking() {
        UUID orderId = placeKeyboardOrder().id();

        awaitProcessed(otherServices.publish(OtherServices.INVENTORY_EVENTS, "StockReleased", orderId, """
                {"items":[{"productId":"%s","quantity":2}]}
                """.formatted(OtherServices.KEYBOARD)));

        assertThat(getOrder(orderId).status()).isEqualTo(OrderStatus.PENDING);
        assertThat(otherServices.read(OtherServices.DEAD_LETTERS, orderId)).isEmpty();
    }

    private ConsumerRecord<String, String> awaitPublished(UUID orderId, String type) {
        eventually().untilAsserted(() -> assertThat(published(orderId, type)).hasSize(1));
        return published(orderId, type).getFirst();
    }

    private List<ConsumerRecord<String, String>> published(UUID orderId, String type) {
        return otherServices.read(OtherServices.ORDER_EVENTS, orderId).stream()
                .filter(record -> JSON.readTree(record.value()).get("type").asString().equals(type))
                .toList();
    }

    // An event of a type the service ignores, sent to each topic after everything else of this order.
    // Once both have been gone through, so has everything that was ahead of them on the same partitions.
    private void awaitEverythingConsumed(UUID orderId) {
        awaitProcessed(otherServices.publish(OtherServices.INVENTORY_EVENTS, "TestMarker", orderId, "{}"));
        awaitProcessed(otherServices.publish(OtherServices.PAYMENT_EVENTS, "TestMarker", orderId, "{}"));
    }
}
