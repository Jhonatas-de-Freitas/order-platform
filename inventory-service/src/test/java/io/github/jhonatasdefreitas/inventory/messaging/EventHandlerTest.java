package io.github.jhonatasdefreitas.inventory.messaging;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.jhonatasdefreitas.inventory.domain.RequestedItem;
import io.github.jhonatasdefreitas.inventory.domain.ReservationService;
import io.github.jhonatasdefreitas.inventory.persistence.ProcessedEvents;
import java.util.List;
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
    private static final UUID KEYBOARD = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MOUSE = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock
    ProcessedEvents processedEvents;

    @Mock
    ReservationService reservations;

    @InjectMocks
    EventHandler handler;

    @BeforeEach
    void everyEventIsNew() {
        lenient().when(processedEvents.markProcessed(any())).thenReturn(true);
    }

    @Test
    void orderCreatedReservesItsItems() {
        handler.handle(event("OrderCreated", """
                {"customerId":"c-1001","customerName":"Ana Souza","customerEmail":"ana@exemplo.com",
                 "items":[{"productId":"%s","quantity":2},{"productId":"%s","quantity":1}]}
                """.formatted(KEYBOARD, MOUSE)));

        verify(reservations).reserve(ORDER_ID, List.of(new RequestedItem(KEYBOARD, 2), new RequestedItem(MOUSE, 1)));
    }

    @Test
    void orderCancelledReleasesWhateverTheReason() {
        handler.handle(event("OrderCancelled", """
                {"customerId":"c-1001","customerName":"Ana Souza","customerEmail":"ana@exemplo.com",
                 "reason":"PAYMENT_REFUSED","detail":"Pagamento recusado: LIMIT_EXCEEDED"}
                """));

        verify(reservations).release(ORDER_ID);
    }

    @Test
    void repeatedEventIsDropped() {
        EventEnvelope event = event("OrderCancelled", "{}");
        when(processedEvents.markProcessed(event.eventId())).thenReturn(false);

        handler.handle(event);

        verifyNoInteractions(reservations);
    }

    @Test
    void unknownTypeIsIgnored() {
        handler.handle(event("OrderConfirmed", """
                {"customerId":"c-1001","totalAmount":699.80,"items":[{"productId":"%s","quantity":2}]}
                """.formatted(KEYBOARD)));

        verifyNoInteractions(reservations);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"customerId\":\"c-1001\"}",
            "{\"items\":[]}",
            "{\"items\":[{\"quantity\":2}]}",
            "{\"items\":[{\"productId\":\"11111111-1111-1111-1111-111111111111\"}]}",
            "{\"items\":[{\"productId\":\"11111111-1111-1111-1111-111111111111\",\"quantity\":0}]}",
            "{\"items\":[{\"productId\":\"keyboard\",\"quantity\":1}]}"
    })
    void orderCreatedThatCannotBeReservedIsInvalid(String payload) {
        EventEnvelope event = event("OrderCreated", payload);

        assertThatThrownBy(() -> handler.handle(event)).isInstanceOf(InvalidEventException.class);
        verifyNoInteractions(reservations);
    }

    private static EventEnvelope event(String type, String payload) {
        return EventEnvelope.parse("""
                {"eventId":"%s","type":"%s","orderId":"%s","occurredAt":"2026-10-10T14:03:22.123Z","payload":%s}
                """.formatted(UUID.randomUUID(), type, ORDER_ID, payload));
    }
}
