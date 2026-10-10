package io.github.jhonatasdefreitas.orders.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderTest {

    private static final UUID KEYBOARD = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MOUSE = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void startsPendingWithoutPrices() {
        Order order = keyboardAndMouseOrder();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getTotalAmount()).isNull();
        assertThat(order.getItemCount()).isEqualTo(2);
        assertThat(order.getItems()).allSatisfy(item -> {
            assertThat(item.productName()).isNull();
            assertThat(item.unitPrice()).isNull();
        });
        assertThat(order.getHistory()).extracting(StatusChange::status).containsExactly(OrderStatus.PENDING);
        assertThat(order.getCreatedAt()).isEqualTo(order.getUpdatedAt());
    }

    @Test
    void reservedStockBringsNamesPricesAndTotal() {
        Order order = keyboardAndMouseOrder();

        order.reserveStock(reservedKeyboardAndMouse(), new BigDecimal("829.7"));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.STOCK_RESERVED);
        assertThat(order.getTotalAmount()).isEqualTo(new BigDecimal("829.70"));
        assertThat(order.getItems()).containsExactly(
                new OrderItem(KEYBOARD, "Teclado Mecânico", 2, new BigDecimal("349.90")),
                new OrderItem(MOUSE, "Mouse Sem Fio", 1, new BigDecimal("129.90")));
    }

    @Test
    void confirmsWhenPaymentIsApprovedAfterTheStock() {
        Order order = keyboardAndMouseOrder();
        order.reserveStock(reservedKeyboardAndMouse(), new BigDecimal("829.70"));

        order.approvePayment();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getHistory()).extracting(StatusChange::status)
                .containsExactly(OrderStatus.PENDING, OrderStatus.STOCK_RESERVED, OrderStatus.CONFIRMED);
    }

    @Test
    void paymentApprovedBeforeTheStockWaitsForIt() {
        Order order = keyboardAndMouseOrder();

        order.approvePayment();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getHistory()).hasSize(1);

        order.reserveStock(reservedKeyboardAndMouse(), new BigDecimal("829.70"));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getTotalAmount()).isEqualTo(new BigDecimal("829.70"));
        assertThat(order.getHistory()).extracting(StatusChange::status)
                .containsExactly(OrderStatus.PENDING, OrderStatus.STOCK_RESERVED, OrderStatus.CONFIRMED);
    }

    @Test
    void cancelsWhilePendingWhenThereIsNoStock() {
        Order order = keyboardAndMouseOrder();

        order.cancel(CancellationReason.OUT_OF_STOCK, "Sem estoque");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancellationReason()).isEqualTo(CancellationReason.OUT_OF_STOCK);
        assertThat(order.getCancellationDetail()).isEqualTo("Sem estoque");
        assertThat(order.getHistory().getLast().detail()).isEqualTo("Sem estoque");
    }

    @Test
    void cancelsAfterTheStockWhenPaymentIsRefused() {
        Order order = keyboardAndMouseOrder();
        order.reserveStock(reservedKeyboardAndMouse(), new BigDecimal("829.70"));

        order.cancel(CancellationReason.PAYMENT_REFUSED, "Pagamento recusado: LIMIT_EXCEEDED");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancellationReason()).isEqualTo(CancellationReason.PAYMENT_REFUSED);
    }

    @Test
    void confirmedOrderIgnoresWhatArrivesLater() {
        Order order = keyboardAndMouseOrder();
        order.reserveStock(reservedKeyboardAndMouse(), new BigDecimal("829.70"));
        order.approvePayment();

        order.cancel(CancellationReason.PAYMENT_REFUSED, "late");
        order.reserveStock(reservedKeyboardAndMouse(), new BigDecimal("1.00"));
        order.approvePayment();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getCancellationReason()).isNull();
        assertThat(order.getTotalAmount()).isEqualTo(new BigDecimal("829.70"));
        assertThat(order.getHistory()).hasSize(3);
    }

    @Test
    void cancelledOrderIgnoresWhatArrivesLater() {
        Order order = keyboardAndMouseOrder();
        order.cancel(CancellationReason.OUT_OF_STOCK, "Sem estoque");

        order.reserveStock(reservedKeyboardAndMouse(), new BigDecimal("829.70"));
        order.approvePayment();
        order.cancel(CancellationReason.PAYMENT_REFUSED, "late");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancellationReason()).isEqualTo(CancellationReason.OUT_OF_STOCK);
        assertThat(order.getTotalAmount()).isNull();
        assertThat(order.getHistory()).hasSize(2);
    }

    @Test
    void secondStockReservationChangesNothing() {
        Order order = keyboardAndMouseOrder();
        order.reserveStock(reservedKeyboardAndMouse(), new BigDecimal("829.70"));

        order.reserveStock(List.of(new ReservedItem(KEYBOARD, "Other", new BigDecimal("1.00"))), new BigDecimal("2.00"));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.STOCK_RESERVED);
        assertThat(order.getTotalAmount()).isEqualTo(new BigDecimal("829.70"));
        assertThat(order.getItems().getFirst().productName()).isEqualTo("Teclado Mecânico");
        assertThat(order.getHistory()).hasSize(2);
    }

    private static Order keyboardAndMouseOrder() {
        return Order.place("c-1001", "Ana Souza", "ana@exemplo.com",
                List.of(OrderItem.requested(KEYBOARD, 2), OrderItem.requested(MOUSE, 1)));
    }

    private static List<ReservedItem> reservedKeyboardAndMouse() {
        return List.of(
                new ReservedItem(KEYBOARD, "Teclado Mecânico", new BigDecimal("349.90")),
                new ReservedItem(MOUSE, "Mouse Sem Fio", new BigDecimal("129.9")));
    }
}
