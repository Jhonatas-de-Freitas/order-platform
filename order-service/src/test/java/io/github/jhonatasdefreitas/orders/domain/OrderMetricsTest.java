package io.github.jhonatasdefreitas.orders.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class OrderMetricsTest {

    private static final UUID KEYBOARD = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final OrderMetrics metrics = new OrderMetrics(meters);
    private final Order order = Order.place("c-1001", "Ana Souza", "ana@exemplo.com",
            List.of(OrderItem.requested(KEYBOARD, 2)));

    @Test
    void countsEveryChangeByStatus() {
        metrics.on(changed(OrderStatus.PENDING, 0));
        metrics.on(changed(OrderStatus.STOCK_RESERVED, 1));
        metrics.on(changed(OrderStatus.CONFIRMED, 2));

        assertThat(statusChanges(OrderStatus.PENDING)).isEqualTo(1);
        assertThat(statusChanges(OrderStatus.STOCK_RESERVED)).isEqualTo(1);
        assertThat(statusChanges(OrderStatus.CONFIRMED)).isEqualTo(1);
        assertThat(meters.find("orders.status.changes").tag("status", "CANCELLED").counter()).isNull();
    }

    @Test
    void timesTheOrderFromCreationUntilItFinishes() {
        metrics.on(changed(OrderStatus.STOCK_RESERVED, 1));
        metrics.on(changed(OrderStatus.CANCELLED, 3));

        Timer cancelled = meters.find("orders.time.to.final").tag("status", "CANCELLED").timer();
        assertThat(cancelled).isNotNull();
        assertThat(cancelled.count()).isEqualTo(1);
        assertThat(cancelled.totalTime(TimeUnit.SECONDS)).isEqualTo(3.0);
    }

    @Test
    void doesNotTimeAStatusThatIsNotFinal() {
        metrics.on(changed(OrderStatus.PENDING, 0));
        metrics.on(changed(OrderStatus.STOCK_RESERVED, 1));

        assertThat(meters.find("orders.time.to.final").timers()).isEmpty();
    }

    private OrderStatusChanged changed(OrderStatus status, int secondsAfterCreation) {
        StatusChange change = new StatusChange(status, order.getCreatedAt().plusSeconds(secondsAfterCreation), null);
        return new OrderStatusChanged(order, change);
    }

    private double statusChanges(OrderStatus status) {
        return meters.counter("orders.status.changes", "status", status.name()).count();
    }
}
