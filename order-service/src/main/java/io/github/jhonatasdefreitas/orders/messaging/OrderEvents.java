package io.github.jhonatasdefreitas.orders.messaging;

import io.github.jhonatasdefreitas.orders.domain.CancellationReason;
import io.github.jhonatasdefreitas.orders.domain.Order;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Payloads of what this service publishes on orders.events. */
final class OrderEvents {

    private OrderEvents() {
    }

    record OrderCreated(String customerId, String customerName, String customerEmail, List<Item> items) {

        record Item(UUID productId, int quantity) {
        }

        static OrderCreated from(Order order) {
            List<Item> items = order.getItems().stream()
                    .map(item -> new Item(item.productId(), item.quantity()))
                    .toList();
            return new OrderCreated(order.getCustomerId(), order.getCustomerName(), order.getCustomerEmail(), items);
        }
    }

    record OrderConfirmed(String customerId, String customerName, String customerEmail,
                          BigDecimal totalAmount, List<Item> items) {

        record Item(UUID productId, String productName, int quantity, BigDecimal unitPrice) {
        }

        static OrderConfirmed from(Order order) {
            List<Item> items = order.getItems().stream()
                    .map(item -> new Item(item.productId(), item.productName(), item.quantity(), item.unitPrice()))
                    .toList();
            return new OrderConfirmed(order.getCustomerId(), order.getCustomerName(), order.getCustomerEmail(),
                    order.getTotalAmount(), items);
        }
    }

    record OrderCancelled(String customerId, String customerName, String customerEmail,
                          CancellationReason reason, String detail) {

        static OrderCancelled from(Order order) {
            return new OrderCancelled(order.getCustomerId(), order.getCustomerName(), order.getCustomerEmail(),
                    order.getCancellationReason(), order.getCancellationDetail());
        }
    }
}
