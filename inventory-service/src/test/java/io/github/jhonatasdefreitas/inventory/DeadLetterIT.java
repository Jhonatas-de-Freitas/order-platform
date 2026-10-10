package io.github.jhonatasdefreitas.inventory;

import static io.github.jhonatasdefreitas.inventory.OtherServices.item;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.github.jhonatasdefreitas.inventory.api.ProductResponse;
import io.github.jhonatasdefreitas.inventory.domain.ReservationStatus;
import io.github.jhonatasdefreitas.inventory.messaging.InvalidEventException;
import io.github.jhonatasdefreitas.inventory.persistence.ProcessedEvents;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

class DeadLetterIT extends IntegrationTestBase {

    // The one place where a failure can be injected without touching the stock itself.
    @MockitoSpyBean
    ProcessedEvents processedEvents;

    @Test
    void messageThatIsNotJsonGoesToTheDeadLetterTopicAndTheNextOnesAreProcessed() {
        ProductResponse keyboard = createProduct("Teclado Mecânico", "349.90", 50);
        UUID orderId = UUID.randomUUID();

        // Same key, so the broken message sits right in front of the good one on the same partition.
        otherServices.publishRaw(OtherServices.ORDER_EVENTS, orderId.toString(), "this is not json");
        otherServices.orderCreated(orderId, item(keyboard.id(), 2));

        awaitPublished(orderId, "StockReserved");
        assertThat(stockOf(keyboard.id())).isEqualTo(48);
        ConsumerRecord<String, String> deadLetter = awaitDeadLetter(orderId);
        assertThat(deadLetter.value()).isEqualTo("this is not json");
        assertThat(header(deadLetter, "kafka_dlt-original-topic")).isEqualTo(OtherServices.ORDER_EVENTS);
        assertThat(header(deadLetter, "kafka_dlt-exception-cause-fqcn")).isEqualTo(InvalidEventException.class.getName());
        assertThat(header(deadLetter, "kafka_dlt-exception-message")).contains("Not a valid event");
    }

    @Test
    void jsonThatIsNotAnEventGoesToTheDeadLetterTopicToo() {
        ProductResponse keyboard = createProduct("Teclado Mecânico", "349.90", 50);
        UUID orderId = UUID.randomUUID();

        otherServices.publishRaw(OtherServices.ORDER_EVENTS, orderId.toString(), """
                {"type":"OrderCreated","orderId":"%s","payload":{"items":[]}}
                """.formatted(orderId));
        otherServices.orderCreated(orderId, item(keyboard.id(), 2));

        awaitPublished(orderId, "StockReserved");
        assertThat(header(awaitDeadLetter(orderId), "kafka_dlt-exception-cause-fqcn"))
                .isEqualTo(InvalidEventException.class.getName());
    }

    @Test
    void orderCreatedWithAnInvalidItemGoesToTheDeadLetterTopicWithoutRetrying() {
        ProductResponse keyboard = createProduct("Teclado Mecânico", "349.90", 50);
        UUID orderId = UUID.randomUUID();

        UUID eventId = otherServices.orderCreated(orderId, item(keyboard.id(), 0));

        ConsumerRecord<String, String> deadLetter = awaitDeadLetter(orderId);
        assertThat(deadLetter.value()).contains(eventId.toString());
        assertThat(header(deadLetter, "kafka_dlt-exception-cause-fqcn")).isEqualTo(InvalidEventException.class.getName());
        assertThat(header(deadLetter, "kafka_dlt-exception-message")).contains("Not a valid OrderCreated payload");
        verify(processedEvents, times(1)).markProcessed(eventId);
        // The failed attempt was rolled back, so the event is not marked as done.
        assertThat(jdbc.sql("select count(*) from processed_events where event_id = ?")
                .param(eventId).query(Long.class).single()).isZero();
        assertThat(stockOf(keyboard.id())).isEqualTo(50);
    }

    @Test
    void failureThatGoesAwayIsRetriedUntilTheStockIsReserved() {
        ProductResponse keyboard = createProduct("Teclado Mecânico", "349.90", 50);
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        doThrow(new TransientDataAccessResourceException("database went away"))
                .doThrow(new TransientDataAccessResourceException("database went away"))
                .doCallRealMethod()
                .when(processedEvents).markProcessed(eventId);

        otherServices.publish(OtherServices.ORDER_EVENTS, eventId, "OrderCreated", orderId,
                OtherServices.orderCreatedPayload(item(keyboard.id(), 2)));

        awaitPublished(orderId, "StockReserved");
        verify(processedEvents, times(3)).markProcessed(eventId);
        assertThat(stockOf(keyboard.id())).isEqualTo(48);
        assertThat(otherServices.read(OtherServices.DEAD_LETTERS, orderId)).isEmpty();
    }

    @Test
    void failureThatStaysGoesToTheDeadLetterTopicAfterTheConfiguredAttempts() {
        ProductResponse keyboard = createProduct("Teclado Mecânico", "349.90", 50);
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        doThrow(new TransientDataAccessResourceException("database went away"))
                .when(processedEvents).markProcessed(eventId);
        double deadLettersBefore = metric("dead_letter_messages_total");

        otherServices.publish(OtherServices.ORDER_EVENTS, eventId, "OrderCreated", orderId,
                OtherServices.orderCreatedPayload(item(keyboard.id(), 2)));
        otherServices.orderCreated(orderId, item(keyboard.id(), 3));

        ConsumerRecord<String, String> deadLetter = awaitDeadLetter(orderId);
        assertThat(deadLetter.value()).contains(eventId.toString());
        assertThat(header(deadLetter, "kafka_dlt-exception-message")).contains("database went away");
        verify(processedEvents, times(4)).markProcessed(eventId);
        // The partition did not stay stuck behind it: the next event of the same order was applied.
        awaitPublished(orderId, "StockReserved");
        assertThat(getReservation(orderId).status()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(stockOf(keyboard.id())).isEqualTo(47);
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
