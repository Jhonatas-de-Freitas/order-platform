package io.github.jhonatasdefreitas.orders.messaging;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.jhonatasdefreitas.orders.domain.CancellationReason;
import io.github.jhonatasdefreitas.orders.domain.OrderService;
import io.github.jhonatasdefreitas.orders.domain.ReservedItem;
import io.github.jhonatasdefreitas.orders.persistence.ProcessedEvents;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EventHandlerTest {

    private static final UUID ORDER_ID = UUID.fromString("7c1f0a34-5b1d-4f6e-8a2b-9d3e4f5a6b7c");
    private static final UUID KEYBOARD = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID HEADSET = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Mock
    ProcessedEvents processedEvents;

    @Mock
    OrderService orders;

    @InjectMocks
    EventHandler handler;

    @BeforeEach
    void everyEventIsNew() {
        lenient().when(processedEvents.markProcessed(any())).thenReturn(true);
    }

    @Test
    void stockReservedPricesTheOrder() {
        handler.handle(event("StockReserved", """
                {"items":[{"productId":"%s","productName":"Teclado Mecânico","quantity":2,"unitPrice":349.90}],
                 "totalAmount":699.80}
                """.formatted(KEYBOARD)));

        verify(orders).reserveStock(ORDER_ID,
                List.of(new ReservedItem(KEYBOARD, "Teclado Mecânico", new BigDecimal("349.90"))),
                new BigDecimal("699.80"));
    }

    @Test
    void stockRejectedCancelsSayingWhatWasMissing() {
        handler.handle(event("StockRejected", """
                {"reason":"INSUFFICIENT_STOCK",
                 "unavailableItems":[{"productId":"%s","requestedQuantity":1,"availableQuantity":0}]}
                """.formatted(HEADSET)));

        verify(orders).cancel(ORDER_ID, CancellationReason.OUT_OF_STOCK,
                HEADSET + ": pedido 1, disponível 0");
    }

    @Test
    void stockRejectedForUnknownProductSaysSo() {
        handler.handle(event("StockRejected", """
                {"reason":"PRODUCT_NOT_FOUND",
                 "unavailableItems":[{"productId":"%s","requestedQuantity":1,"availableQuantity":0}]}
                """.formatted(HEADSET)));

        verify(orders).cancel(ORDER_ID, CancellationReason.OUT_OF_STOCK, "Produto não encontrado: " + HEADSET);
    }

    @Test
    void paymentApprovedConfirms() {
        handler.handle(event("PaymentApproved", """
                {"paymentId":"a3c1","amount":699.80}
                """));

        verify(orders).approvePayment(ORDER_ID);
    }

    @Test
    void paymentRefusedCancelsWithTheReason() {
        handler.handle(event("PaymentRefused", """
                {"paymentId":"a3c1","amount":7499.00,"reason":"LIMIT_EXCEEDED"}
                """));

        verify(orders).cancel(ORDER_ID, CancellationReason.PAYMENT_REFUSED, "Pagamento recusado: LIMIT_EXCEEDED");
    }

    @Test
    void repeatedEventIsDropped() {
        EventEnvelope event = event("PaymentApproved", "{}");
        when(processedEvents.markProcessed(event.eventId())).thenReturn(false);

        handler.handle(event);

        verifyNoInteractions(orders);
    }

    @Test
    void unknownTypeIsIgnored() {
        handler.handle(event("StockReleased", """
                {"items":[{"productId":"%s","quantity":1}]}
                """.formatted(KEYBOARD)));

        verifyNoInteractions(orders);
    }

    @Test
    void stockReservedWithoutTotalIsInvalid() {
        EventEnvelope event = event("StockReserved", """
                {"items":[]}
                """);

        assertThatThrownBy(() -> handler.handle(event)).isInstanceOf(InvalidEventException.class);
        verifyNoInteractions(orders);
    }

    private static EventEnvelope event(String type, String payload) {
        return EventEnvelope.parse("""
                {"eventId":"%s","type":"%s","orderId":"%s","occurredAt":"2026-10-10T14:03:22.123Z","payload":%s}
                """.formatted(UUID.randomUUID(), type, ORDER_ID, payload));
    }
}
