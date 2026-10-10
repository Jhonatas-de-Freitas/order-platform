package io.github.jhonatasdefreitas.payments;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jhonatasdefreitas.payments.api.PaymentResponse;
import io.github.jhonatasdefreitas.payments.domain.PaymentStatus;
import io.github.jhonatasdefreitas.payments.domain.RefusalReason;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class PaymentEventsIT extends IntegrationTestBase {

    @Test
    void approvesTheChargeAndTellsThatItWasApproved() {
        UUID orderId = UUID.randomUUID();

        otherServices.stockReserved(orderId, "699.80");

        ConsumerRecord<String, String> record = awaitPublished(orderId, "PaymentApproved");
        PaymentResponse payment = getPayment(orderId);
        assertThat(payment.orderId()).isEqualTo(orderId);
        assertThat(payment.amount()).isEqualTo(new BigDecimal("699.80"));
        assertThat(payment.status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(payment.reason()).isNull();

        assertThat(record.key()).isEqualTo(orderId.toString());
        assertThat(new String(record.headers().lastHeader("event-type").value(), StandardCharsets.UTF_8))
                .isEqualTo("PaymentApproved");
        // The raw text, to be sure money leaves with two decimal places.
        assertThat(record.value()).contains("\"amount\":699.80");
        JsonNode event = JSON.readTree(record.value());
        assertThat(UUID.fromString(event.get("eventId").asString())).isNotNull();
        assertThat(event.get("type").asString()).isEqualTo("PaymentApproved");
        assertThat(event.get("orderId").asString()).isEqualTo(orderId.toString());
        assertThat(event.get("occurredAt").asString()).isEqualTo(payment.createdAt().toString());
        assertThat(event.get("payload")).isEqualTo(JSON.readTree("""
                {"paymentId":"%s","amount":699.80}
                """.formatted(payment.id())));
    }

    @Test
    void refusesAChargeAboveTheLimitAndTellsWhy() {
        UUID orderId = UUID.randomUUID();

        otherServices.stockReserved(orderId, "7499.00");

        ConsumerRecord<String, String> record = awaitPublished(orderId, "PaymentRefused");
        PaymentResponse payment = getPayment(orderId);
        assertThat(payment.amount()).isEqualTo(new BigDecimal("7499.00"));
        assertThat(payment.status()).isEqualTo(PaymentStatus.REFUSED);
        assertThat(payment.reason()).isEqualTo(RefusalReason.LIMIT_EXCEEDED);

        assertThat(new String(record.headers().lastHeader("event-type").value(), StandardCharsets.UTF_8))
                .isEqualTo("PaymentRefused");
        assertThat(record.value()).contains("\"amount\":7499.00");
        assertThat(payloadOf(record)).isEqualTo(JSON.readTree("""
                {"paymentId":"%s","amount":7499.00,"reason":"LIMIT_EXCEEDED"}
                """.formatted(payment.id())));
        assertThat(published(orderId, "PaymentApproved")).isEmpty();
    }

    @Test
    void chargeOfExactlyTheLimitIsApproved() {
        UUID atTheLimit = UUID.randomUUID();
        UUID oneCentAbove = UUID.randomUUID();

        otherServices.stockReserved(atTheLimit, "5000.00");
        otherServices.stockReserved(oneCentAbove, "5000.01");

        awaitPublished(atTheLimit, "PaymentApproved");
        awaitPublished(oneCentAbove, "PaymentRefused");
    }

    @Test
    void sameStockReservedDeliveredTwiceChargesOnce() {
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        String payload = OtherServices.stockReservedPayload("699.80");

        otherServices.publish(OtherServices.INVENTORY_EVENTS, eventId, "StockReserved", orderId, payload);
        otherServices.publish(OtherServices.INVENTORY_EVENTS, eventId, "StockReserved", orderId, payload);
        awaitEverythingConsumed(orderId);

        assertThat(paymentCount(orderId)).isEqualTo(1);
        assertThat(outboxCount(orderId)).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from processed_events where event_id = ?")
                .param(eventId).query(Long.class).single()).isEqualTo(1);
        eventually().untilAsserted(() -> assertThat(otherServices.read(OtherServices.PAYMENT_EVENTS, orderId))
                .extracting(IntegrationTestBase::typeOf)
                .containsExactly("PaymentApproved"));
    }

    @Test
    void sameOrderInADifferentEventIsNotChargedAgain() {
        UUID orderId = UUID.randomUUID();

        otherServices.stockReserved(orderId, "699.80");
        // Above the limit: if this one were charged too, it would show up as a refusal.
        awaitProcessed(otherServices.stockReserved(orderId, "7499.00"));

        assertThat(paymentCount(orderId)).isEqualTo(1);
        assertThat(outboxCount(orderId)).isEqualTo(1);
        PaymentResponse payment = getPayment(orderId);
        assertThat(payment.status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(payment.amount()).isEqualTo(new BigDecimal("699.80"));
    }

    @Test
    void orderThatDidNotGetItsStockIsNotCharged() {
        UUID orderId = UUID.randomUUID();

        awaitProcessed(otherServices.publish(OtherServices.INVENTORY_EVENTS, "StockRejected", orderId, """
                {"reason":"INSUFFICIENT_STOCK",
                 "unavailableItems":[{"productId":"44444444-4444-4444-4444-444444444444",
                                      "requestedQuantity":1,"availableQuantity":0}]}
                """));

        assertNoPayment(orderId);
        assertThat(outboxCount(orderId)).isZero();
        assertThat(otherServices.read(OtherServices.DEAD_LETTERS, orderId)).isEmpty();
    }

    @Test
    void stockGivenBackAfterARefusalChangesNothing() {
        UUID orderId = UUID.randomUUID();
        otherServices.stockReserved(orderId, "7499.00");
        awaitPublished(orderId, "PaymentRefused");

        awaitProcessed(otherServices.publish(OtherServices.INVENTORY_EVENTS, "StockReleased", orderId, """
                {"items":[{"productId":"55555555-5555-5555-5555-555555555555","quantity":1}]}
                """));

        assertThat(getPayment(orderId).status()).isEqualTo(PaymentStatus.REFUSED);
        assertThat(outboxCount(orderId)).isEqualTo(1);
        assertThat(otherServices.read(OtherServices.DEAD_LETTERS, orderId)).isEmpty();
    }

    @Test
    void countsPaymentsByOutcome() {
        // The counters are shared by every test of this context, so only the difference is checked.
        String approved = "payments_total{outcome=\"approved\"}";
        String refused = "payments_total{outcome=\"refused\"}";
        double approvedBefore = metric(approved);
        double refusedBefore = metric(refused);
        UUID cheap = UUID.randomUUID();
        UUID expensive = UUID.randomUUID();

        otherServices.stockReserved(cheap, "129.90");
        otherServices.stockReserved(expensive, "9999.00");
        awaitPublished(cheap, "PaymentApproved");
        awaitPublished(expensive, "PaymentRefused");

        // Counted after the commit, so a moment after the event is written.
        eventually().untilAsserted(() -> {
            assertThat(metric(approved)).isGreaterThanOrEqualTo(approvedBefore + 1);
            assertThat(metric(refused)).isGreaterThanOrEqualTo(refusedBefore + 1);
        });
    }

    private long paymentCount(UUID orderId) {
        return jdbc.sql("select count(*) from payments where order_id = ?")
                .param(orderId)
                .query(Long.class)
                .single();
    }

    // An event of a type the service ignores, sent after everything else of this order.
    // Once it has been gone through, so has everything that was ahead of it on the same partition.
    private void awaitEverythingConsumed(UUID orderId) {
        awaitProcessed(otherServices.publish(OtherServices.INVENTORY_EVENTS, "TestMarker", orderId, "{}"));
    }
}
