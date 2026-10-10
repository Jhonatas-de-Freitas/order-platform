package io.github.jhonatasdefreitas.inventory;

import static io.github.jhonatasdefreitas.inventory.OtherServices.item;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.jhonatasdefreitas.inventory.api.ProductResponse;
import io.github.jhonatasdefreitas.inventory.domain.RequestedItem;
import io.github.jhonatasdefreitas.inventory.domain.ReservationService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ConcurrentReservationsIT extends IntegrationTestBase {

    @Autowired
    ReservationService reservations;

    @Test
    void twoOrdersForTheLastUnitOneIsReservedAndTheOtherRejected() {
        ProductResponse monitor = createProduct("Monitor 4K", "2199.00", 1);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        otherServices.orderCreated(first, item(monitor.id(), 1));
        otherServices.orderCreated(second, item(monitor.id(), 1));

        eventually().untilAsserted(() -> assertThat(outboxCount(first) + outboxCount(second)).isEqualTo(2));
        assertThat(outboxCount(first, "StockReserved") + outboxCount(second, "StockReserved")).isEqualTo(1);
        assertThat(outboxCount(first, "StockRejected") + outboxCount(second, "StockRejected")).isEqualTo(1);
        UUID rejected = outboxCount(first, "StockRejected") == 1 ? first : second;
        assertThat(payloadOf(awaitPublished(rejected, "StockRejected"))).isEqualTo(JSON.readTree("""
                {"reason":"INSUFFICIENT_STOCK",
                 "unavailableItems":[{"productId":"%s","requestedQuantity":1,"availableQuantity":0}]}
                """.formatted(monitor.id())));
        assertThat(stockOf(monitor.id())).isZero();
    }

    @Test
    void ordersRacingForTheSameProductNeverTakeMoreThanThereIs() throws Exception {
        ProductResponse webcam = createProduct("Webcam Full HD", "189.00", 5);
        List<UUID> orders = Stream.generate(UUID::randomUUID).limit(16).toList();

        allAtOnce(orders.stream()
                .map(orderId -> (Runnable) () ->
                        reservations.reserve(orderId, List.of(new RequestedItem(webcam.id(), 1))))
                .toList());

        assertThat(stockOf(webcam.id())).isZero();
        assertThat(orders.stream().mapToLong(orderId -> outboxCount(orderId, "StockReserved")).sum()).isEqualTo(5);
        assertThat(orders.stream().mapToLong(orderId -> outboxCount(orderId, "StockRejected")).sum()).isEqualTo(11);
        assertThat(jdbc.sql("""
                select coalesce(sum(i.quantity), 0) from reservation_items i
                join reservations r on r.id = i.reservation_id
                where i.product_id = ? and r.status = 'RESERVED'
                """).param(webcam.id()).query(Long.class).single()).isEqualTo(5);
    }

    @Test
    void ordersThatShareProductsInOppositeOrderDoNotDeadlock() throws Exception {
        ProductResponse keyboard = createProduct("Teclado Mecânico", "349.90", 100);
        ProductResponse mouse = createProduct("Mouse Sem Fio", "129.90", 100);
        RequestedItem oneKeyboard = new RequestedItem(keyboard.id(), 1);
        RequestedItem oneMouse = new RequestedItem(mouse.id(), 1);
        List<Runnable> orders = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            orders.add(() -> reservations.reserve(UUID.randomUUID(), List.of(oneKeyboard, oneMouse)));
            orders.add(() -> reservations.reserve(UUID.randomUUID(), List.of(oneMouse, oneKeyboard)));
        }

        // A deadlock would not hang here: PostgreSQL detects it and fails one of the two transactions.
        allAtOnce(orders);

        assertThat(stockOf(keyboard.id())).isEqualTo(60);
        assertThat(stockOf(mouse.id())).isEqualTo(60);
    }

    private long outboxCount(UUID orderId) {
        return jdbc.sql("select count(*) from outbox where message_key = ?")
                .param(orderId.toString())
                .query(Long.class)
                .single();
    }

    /** Runs every task on its own thread, all released at the same instant, and fails if any of them fails. */
    private static void allAtOnce(List<Runnable> tasks) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService threads = Executors.newFixedThreadPool(tasks.size())) {
            List<Future<Void>> running = new ArrayList<>();
            for (Runnable task : tasks) {
                running.add(threads.submit(() -> {
                    start.await();
                    task.run();
                    return null;
                }));
            }
            start.countDown();
            for (Future<Void> task : running) {
                task.get(30, TimeUnit.SECONDS);
            }
        }
    }
}
