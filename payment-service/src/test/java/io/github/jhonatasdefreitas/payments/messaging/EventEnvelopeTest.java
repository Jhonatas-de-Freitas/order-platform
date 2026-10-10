package io.github.jhonatasdefreitas.payments.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class EventEnvelopeTest {

    private static final UUID ORDER_ID = UUID.fromString("7c1f0a34-5b1d-4f6e-8a2b-9d3e4f5a6b7c");

    record Priced(BigDecimal totalAmount, List<String> tags) {
    }

    @Test
    void writesTheEnvelopeOfTheContract() {
        EventEnvelope envelope = EventEnvelope.of("PaymentApproved", ORDER_ID,
                Instant.parse("2026-10-10T14:03:22.123Z"), new Priced(new BigDecimal("699.80"), List.of("a")));

        assertThat(envelope.toJson()).isEqualTo("""
                {"eventId":"%s","type":"PaymentApproved","orderId":"7c1f0a34-5b1d-4f6e-8a2b-9d3e4f5a6b7c",\
                "occurredAt":"2026-10-10T14:03:22.123Z","payload":{"totalAmount":699.80,"tags":["a"]}}\
                """.formatted(envelope.eventId()));
    }

    @Test
    void readsWhatItWrote() {
        EventEnvelope written = EventEnvelope.of("PaymentApproved", ORDER_ID,
                Instant.parse("2026-10-10T14:03:22.123Z"), new Priced(new BigDecimal("699.80"), List.of("a", "b")));

        EventEnvelope read = EventEnvelope.parse(written.toJson());

        assertThat(read.eventId()).isEqualTo(written.eventId());
        assertThat(read.type()).isEqualTo("PaymentApproved");
        assertThat(read.orderId()).isEqualTo(ORDER_ID);
        assertThat(read.occurredAt()).isEqualTo(Instant.parse("2026-10-10T14:03:22.123Z"));
        assertThat(read.payloadAs(Priced.class).totalAmount()).isEqualByComparingTo("699.80");
        assertThat(read.payloadAs(Priced.class).tags()).containsExactly("a", "b");
    }

    @Test
    void ignoresFieldsItDoesNotKnow() {
        EventEnvelope envelope = EventEnvelope.parse("""
                {"eventId":"0b9d6c1e-4a7b-4f0e-9a51-0c6f3d2a8e11","type":"StockReserved",
                 "orderId":"7c1f0a34-5b1d-4f6e-8a2b-9d3e4f5a6b7c","occurredAt":"2026-10-10T14:03:22.123Z",
                 "traceId":"abc","payload":{"totalAmount":10.00,"tags":[],"warehouse":"SP-01"}}
                """);

        assertThat(envelope.type()).isEqualTo("StockReserved");
        assertThat(envelope.payloadAs(Priced.class).totalAmount()).isEqualByComparingTo("10.00");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "",
            "this is not json",
            "null",
            "42",
            "{\"type\":\"StockReserved\"",
            "{\"type\":\"StockReserved\",\"orderId\":\"7c1f0a34-5b1d-4f6e-8a2b-9d3e4f5a6b7c\"}",
            "{\"eventId\":\"0b9d6c1e-4a7b-4f0e-9a51-0c6f3d2a8e11\",\"type\":\"StockReserved\",\"orderId\":\"not-a-uuid\"}"
    })
    void refusesWhatIsNotAnEvent(String message) {
        assertThatThrownBy(() -> EventEnvelope.parse(message))
                .isInstanceOf(InvalidEventException.class)
                .hasMessageStartingWith("Not a valid event");
    }

    @Test
    void refusesPayloadOfTheWrongShape() {
        EventEnvelope envelope = EventEnvelope.parse("""
                {"eventId":"0b9d6c1e-4a7b-4f0e-9a51-0c6f3d2a8e11","type":"StockReserved",
                 "orderId":"7c1f0a34-5b1d-4f6e-8a2b-9d3e4f5a6b7c","payload":{"totalAmount":"a lot"}}
                """);

        assertThatThrownBy(() -> envelope.payloadAs(Priced.class))
                .isInstanceOf(InvalidEventException.class)
                .hasMessageStartingWith("Not a valid StockReserved payload");
    }

    @Test
    void refusesMissingPayloadWhenOneIsExpected() {
        EventEnvelope envelope = EventEnvelope.parse("""
                {"eventId":"0b9d6c1e-4a7b-4f0e-9a51-0c6f3d2a8e11","type":"StockReserved",
                 "orderId":"7c1f0a34-5b1d-4f6e-8a2b-9d3e4f5a6b7c"}
                """);

        assertThatThrownBy(() -> envelope.payloadAs(Priced.class))
                .isInstanceOf(InvalidEventException.class);
    }
}
