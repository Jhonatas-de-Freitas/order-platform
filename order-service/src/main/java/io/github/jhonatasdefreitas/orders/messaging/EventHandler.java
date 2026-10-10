package io.github.jhonatasdefreitas.orders.messaging;

import io.github.jhonatasdefreitas.orders.domain.CancellationReason;
import io.github.jhonatasdefreitas.orders.domain.OrderService;
import io.github.jhonatasdefreitas.orders.domain.ReservedItem;
import io.github.jhonatasdefreitas.orders.persistence.ProcessedEvents;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class EventHandler {

    private static final Logger log = LoggerFactory.getLogger(EventHandler.class);

    private final ProcessedEvents processedEvents;
    private final OrderService orders;

    public EventHandler(ProcessedEvents processedEvents, OrderService orders) {
        this.processedEvents = processedEvents;
        this.orders = orders;
    }

    // One transaction for the "already seen" mark and for the effect on the order:
    // if the effect fails, the mark is rolled back too and the retry starts clean.
    @Transactional
    public void handle(EventEnvelope event) {
        if (!processedEvents.markProcessed(event.eventId())) {
            log.info("Skipping {} {}: already processed", event.type(), event.eventId());
            return;
        }
        UUID orderId = event.orderId();
        switch (event.type()) {
            case "StockReserved" -> {
                StockReserved reserved = event.payloadAs(StockReserved.class);
                orders.reserveStock(orderId, reserved.items(), reserved.totalAmount());
            }
            case "StockRejected" -> {
                StockRejected rejected = event.payloadAs(StockRejected.class);
                orders.cancel(orderId, CancellationReason.OUT_OF_STOCK, rejected.detail());
            }
            case "PaymentApproved" -> orders.approvePayment(orderId);
            case "PaymentRefused" -> {
                PaymentRefused refused = event.payloadAs(PaymentRefused.class);
                orders.cancel(orderId, CancellationReason.PAYMENT_REFUSED, "Pagamento recusado: " + refused.reason());
            }
            default -> log.debug("Ignoring {}: nothing to do for this type", event.type());
        }
    }

    // Only the fields this service uses. Anything else in the payload is ignored.

    record StockReserved(List<ReservedItem> items, BigDecimal totalAmount) {

        StockReserved {
            Objects.requireNonNull(items, "items is required");
            Objects.requireNonNull(totalAmount, "totalAmount is required");
        }
    }

    record StockRejected(String reason, List<UnavailableItem> unavailableItems) {

        record UnavailableItem(UUID productId, int requestedQuantity, int availableQuantity) {
        }

        // Only the product id is known here: the event has no name and the order gets names from StockReserved.
        String detail() {
            if (unavailableItems == null || unavailableItems.isEmpty()) {
                return "Sem estoque";
            }
            if ("PRODUCT_NOT_FOUND".equals(reason)) {
                return unavailableItems.stream()
                        .map(item -> String.valueOf(item.productId()))
                        .collect(Collectors.joining(", ", "Produto não encontrado: ", ""));
            }
            return unavailableItems.stream()
                    .map(item -> "%s: pedido %d, disponível %d"
                            .formatted(item.productId(), item.requestedQuantity(), item.availableQuantity()))
                    .collect(Collectors.joining("; "));
        }
    }

    record PaymentRefused(String reason) {
    }
}
