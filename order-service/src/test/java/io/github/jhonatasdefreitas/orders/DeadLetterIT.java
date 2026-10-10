package io.github.jhonatasdefreitas.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.github.jhonatasdefreitas.orders.domain.OrderNotFoundException;
import io.github.jhonatasdefreitas.orders.domain.OrderStatus;
import io.github.jhonatasdefreitas.orders.messaging.InvalidEventException;
import io.github.jhonatasdefreitas.orders.persistence.ProcessedEvents;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

class DeadLetterIT extends IntegrationTestBase {

    // The one place where a failure can be injected without touching the order itself.
    @MockitoSpyBean
    ProcessedEvents processedEvents;

    @Test
    void messageThatIsNotJsonGoesToTheDeadLetterTopicAndTheNextOnesAreProcessed() {
        UUID orderId = placeKeyboardOrder().id();

        // Same key, so the broken message sits right in front of the good one on the same partition.
        otherServices.publishRaw(OtherServices.INVENTORY_EVENTS, orderId.toString(), "this is not json");
        otherServices.stockReserved(orderId);

        awaitStatus(orderId, OrderStatus.STOCK_RESERVED);
        ConsumerRecord<String, String> deadLetter = awaitDeadLetter(orderId);
        assertThat(deadLetter.value()).isEqualTo("this is not json");
        assertThat(header(deadLetter, "kafka_dlt-original-topic")).isEqualTo(OtherServices.INVENTORY_EVENTS);
        assertThat(header(deadLetter, "kafka_dlt-exception-cause-fqcn")).isEqualTo(InvalidEventException.class.getName());
        assertThat(header(deadLetter, "kafka_dlt-exception-message")).contains("Not a valid event");
    }

    @Test
    void jsonThatIsNotAnEventGoesToTheDeadLetterTopicToo() {
        UUID orderId = placeKeyboardOrder().id();

        otherServices.publishRaw(OtherServices.PAYMENT_EVENTS, orderId.toString(), """
                {"type":"PaymentApproved","orderId":"%s","payload":{}}
                """.formatted(orderId));
        otherServices.publish(OtherServices.INVENTORY_EVENTS, "StockReserved", orderId, """
                {"items":[]}
                """);
        otherServices.stockReserved(orderId);

        awaitStatus(orderId, OrderStatus.STOCK_RESERVED);
        eventually().untilAsserted(() ->
                assertThat(otherServices.read(OtherServices.DEAD_LETTERS, orderId)).hasSize(2));
        assertThat(otherServices.read(OtherServices.DEAD_LETTERS, orderId))
                .extracting(record -> header(record, "kafka_dlt-original-topic"))
                .containsExactlyInAnyOrder(OtherServices.PAYMENT_EVENTS, OtherServices.INVENTORY_EVENTS);
    }

    @Test
    void eventForAnOrderThatDoesNotExistGoesToTheDeadLetterTopicWithoutRetrying() {
        UUID unknownOrder = UUID.randomUUID();

        UUID eventId = otherServices.paymentApproved(unknownOrder);

        ConsumerRecord<String, String> deadLetter = awaitDeadLetter(unknownOrder);
        assertThat(header(deadLetter, "kafka_dlt-exception-cause-fqcn")).isEqualTo(OrderNotFoundException.class.getName());
        assertThat(header(deadLetter, "kafka_dlt-exception-message")).contains("Order " + unknownOrder + " not found");
        verify(processedEvents, times(1)).markProcessed(eventId);
        // The failed attempt was rolled back, so the event is not marked as done.
        assertThat(jdbc.sql("select count(*) from processed_events where event_id = ?")
                .param(eventId).query(Long.class).single()).isZero();
    }

    @Test
    void failureThatGoesAwayIsRetriedUntilTheEventIsApplied() {
        UUID orderId = placeKeyboardOrder().id();
        UUID eventId = UUID.randomUUID();
        doThrow(new TransientDataAccessResourceException("database went away"))
                .doThrow(new TransientDataAccessResourceException("database went away"))
                .doCallRealMethod()
                .when(processedEvents).markProcessed(eventId);

        otherServices.publish(OtherServices.PAYMENT_EVENTS, eventId, "PaymentRefused", orderId, """
                {"paymentId":"a3c1","amount":699.80,"reason":"LIMIT_EXCEEDED"}
                """);

        awaitStatus(orderId, OrderStatus.CANCELLED);
        verify(processedEvents, times(3)).markProcessed(eventId);
        assertThat(otherServices.read(OtherServices.DEAD_LETTERS, orderId)).isEmpty();
    }

    @Test
    void failureThatStaysGoesToTheDeadLetterTopicAfterTheConfiguredAttempts() {
        UUID orderId = placeKeyboardOrder().id();
        UUID eventId = UUID.randomUUID();
        doThrow(new TransientDataAccessResourceException("database went away"))
                .when(processedEvents).markProcessed(eventId);
        double deadLettersBefore = metric("dead_letter_messages_total");

        otherServices.publish(OtherServices.INVENTORY_EVENTS, eventId, "StockRejected", orderId, """
                {"reason":"INSUFFICIENT_STOCK","unavailableItems":[]}
                """);
        otherServices.stockReserved(orderId);

        ConsumerRecord<String, String> deadLetter = awaitDeadLetter(orderId);
        assertThat(deadLetter.value()).contains(eventId.toString());
        assertThat(header(deadLetter, "kafka_dlt-exception-message")).contains("database went away");
        verify(processedEvents, times(4)).markProcessed(eventId);
        // The partition did not stay stuck behind it.
        awaitStatus(orderId, OrderStatus.STOCK_RESERVED);
        assertThat(metric("dead_letter_messages_total")).isEqualTo(deadLettersBefore + 1);
    }

    private ConsumerRecord<String, String> awaitDeadLetter(UUID key) {
        eventually().untilAsserted(() ->
                assertThat(otherServices.read(OtherServices.DEAD_LETTERS, key)).hasSize(1));
        return otherServices.read(OtherServices.DEAD_LETTERS, key).getFirst();
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }
}
