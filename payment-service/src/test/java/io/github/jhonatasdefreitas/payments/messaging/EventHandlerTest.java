package io.github.jhonatasdefreitas.payments.messaging;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.jhonatasdefreitas.payments.domain.PaymentService;
import io.github.jhonatasdefreitas.payments.persistence.ProcessedEvents;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EventHandlerTest {

    private static final UUID ORDER_ID = UUID.fromString("7c1f0a34-5b1d-4f6e-8a2b-9d3e4f5a6b7c");

    @Mock
    ProcessedEvents processedEvents;

    @Mock
    PaymentService payments;

    @InjectMocks
    EventHandler handler;

    @BeforeEach
    void everyEventIsNew() {
        lenient().when(processedEvents.markProcessed(any())).thenReturn(true);
    }

    @Test
    void stockReservedChargesTheTotalOfTheOrder() {
        handler.handle(event("StockReserved", """
                {"items":[{"productId":"11111111-1111-1111-1111-111111111111","productName":"Teclado Mecânico",
                           "quantity":2,"unitPrice":349.90}],
                 "totalAmount":699.80}
                """));

        verify(payments).charge(ORDER_ID, new BigDecimal("699.80"));
    }

    @Test
    void repeatedEventIsDropped() {
        EventEnvelope event = event("StockReserved", """
                {"items":[],"totalAmount":699.80}
                """);
        when(processedEvents.markProcessed(event.eventId())).thenReturn(false);

        handler.handle(event);

        verifyNoInteractions(payments);
    }

    @ParameterizedTest
    @ValueSource(strings = {"StockRejected", "StockReleased", "SomethingNew"})
    void otherTypesAreIgnored(String type) {
        handler.handle(event(type, """
                {"reason":"INSUFFICIENT_STOCK","items":[],"totalAmount":699.80}
                """));

        verifyNoInteractions(payments);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"items\":[]}",
            "{\"totalAmount\":null}",
            "{\"totalAmount\":0}",
            "{\"totalAmount\":-10.00}",
            "{\"totalAmount\":\"a lot\"}"
    })
    void stockReservedWithoutAnAmountToChargeIsInvalid(String payload) {
        EventEnvelope event = event("StockReserved", payload);

        assertThatThrownBy(() -> handler.handle(event)).isInstanceOf(InvalidEventException.class);
        verifyNoInteractions(payments);
    }

    private static EventEnvelope event(String type, String payload) {
        return EventEnvelope.parse("""
                {"eventId":"%s","type":"%s","orderId":"%s","occurredAt":"2026-10-10T14:03:22.123Z","payload":%s}
                """.formatted(UUID.randomUUID(), type, ORDER_ID, payload));
    }
}
