package io.github.jhonatasdefreitas.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.github.jhonatasdefreitas.payments.domain.PaymentStatus;
import io.github.jhonatasdefreitas.payments.messaging.InvalidEventException;
import io.github.jhonatasdefreitas.payments.persistence.ProcessedEvents;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

class DeadLetterIT extends IntegrationTestBase {

    // The one place where a failure can be injected without touching the payment itself.
    @MockitoSpyBean
    ProcessedEvents processedEvents;

    @Test
    void messageThatIsNotJsonGoesToTheDeadLetterTopicAndTheNextOnesAreProcessed() {
        UUID orderId = UUID.randomUUID();

        // Same key, so the broken message sits right in front of the good one on the same partition.
        otherServices.publishRaw(OtherServices.INVENTORY_EVENTS, orderId.toString(), "this is not json");
        otherServices.stockReserved(orderId, "699.80");

        awaitPublished(orderId, "PaymentApproved");
        ConsumerRecord<String, String> deadLetter = awaitDeadLetter(orderId);
        assertThat(deadLetter.value()).isEqualTo("this is not json");
        assertThat(header(deadLetter, "kafka_dlt-original-topic")).isEqualTo(OtherServices.INVENTORY_EVENTS);
        assertThat(header(deadLetter, "kafka_dlt-exception-cause-fqcn")).isEqualTo(InvalidEventException.class.getName());
        assertThat(header(deadLetter, "kafka_dlt-exception-message")).contains("Not a valid event");
    }

    @Test
    void jsonThatIsNotAnEventGoesToTheDeadLetterTopicToo() {
        UUID orderId = UUID.randomUUID();

        otherServices.publishRaw(OtherServices.INVENTORY_EVENTS, orderId.toString(), """
                {"type":"StockReserved","orderId":"%s","payload":{"totalAmount":699.80}}
                """.formatted(orderId));
        otherServices.stockReserved(orderId, "699.80");

        awaitPublished(orderId, "PaymentApproved");
        assertThat(header(awaitDeadLetter(orderId), "kafka_dlt-exception-cause-fqcn"))
                .isEqualTo(InvalidEventException.class.getName());
    }

    @Test
    void stockReservedWithoutATotalGoesToTheDeadLetterTopicWithoutRetrying() {
        UUID orderId = UUID.randomUUID();

        UUID eventId = otherServices.publish(OtherServices.INVENTORY_EVENTS, "StockReserved", orderId, """
                {"items":[{"productId":"11111111-1111-1111-1111-111111111111","quantity":2}]}
                """);

        ConsumerRecord<String, String> deadLetter = awaitDeadLetter(orderId);
        assertThat(deadLetter.value()).contains(eventId.toString());
        assertThat(header(deadLetter, "kafka_dlt-exception-cause-fqcn")).isEqualTo(InvalidEventException.class.getName());
        assertThat(header(deadLetter, "kafka_dlt-exception-message")).contains("Not a valid StockReserved payload");
        verify(processedEvents, times(1)).markProcessed(eventId);
        // The failed attempt was rolled back, so the event is not marked as done.
        assertThat(jdbc.sql("select count(*) from processed_events where event_id = ?")
                .param(eventId).query(Long.class).single()).isZero();
        assertNoPayment(orderId);
    }

    @Test
    void failureThatGoesAwayIsRetriedUntilTheOrderIsCharged() {
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        doThrow(new TransientDataAccessResourceException("database went away"))
                .doThrow(new TransientDataAccessResourceException("database went away"))
                .doCallRealMethod()
                .when(processedEvents).markProcessed(eventId);

        otherServices.publish(OtherServices.INVENTORY_EVENTS, eventId, "StockReserved", orderId,
                OtherServices.stockReservedPayload("699.80"));

        awaitPublished(orderId, "PaymentApproved");
        verify(processedEvents, times(3)).markProcessed(eventId);
        assertThat(otherServices.read(OtherServices.DEAD_LETTERS, orderId)).isEmpty();
    }

    @Test
    void failureThatStaysGoesToTheDeadLetterTopicAfterTheConfiguredAttempts() {
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        doThrow(new TransientDataAccessResourceException("database went away"))
                .when(processedEvents).markProcessed(eventId);
        double deadLettersBefore = deadLetterCount();

        otherServices.publish(OtherServices.INVENTORY_EVENTS, eventId, "StockReserved", orderId,
                OtherServices.stockReservedPayload("7499.00"));
        otherServices.stockReserved(orderId, "699.80");

        ConsumerRecord<String, String> deadLetter = awaitDeadLetter(orderId);
        assertThat(deadLetter.value()).contains(eventId.toString());
        assertThat(header(deadLetter, "kafka_dlt-exception-message")).contains("database went away");
        verify(processedEvents, times(4)).markProcessed(eventId);
        // The partition did not stay stuck behind it: the next event of the same order was applied.
        awaitPublished(orderId, "PaymentApproved");
        assertThat(getPayment(orderId).status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(deadLetterCount()).isEqualTo(deadLettersBefore + 1);
    }

    private ConsumerRecord<String, String> awaitDeadLetter(UUID key) {
        eventually().untilAsserted(() ->
                assertThat(otherServices.read(OtherServices.DEAD_LETTERS, key)).hasSize(1));
        return otherServices.read(OtherServices.DEAD_LETTERS, key).getFirst();
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    private double deadLetterCount() {
        String metrics = client.get().uri("/actuator/prometheus")
                .exchange()
                .expectBody(String.class)
                .returnResult().getResponseBody();
        return List.of(metrics.split("\n")).stream()
                .filter(line -> line.startsWith("dead_letter_messages_total"))
                .mapToDouble(line -> Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1)))
                .sum();
    }
}
