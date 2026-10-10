package io.github.jhonatasdefreitas.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ReservationTest {

    private static final UUID ORDER_ID = UUID.fromString("7c1f0a34-5b1d-4f6e-8a2b-9d3e4f5a6b7c");

    private final Product keyboard = product("11111111-1111-1111-1111-111111111111", "Teclado Mecânico", "349.90", 50);
    private final Product mouse = product("22222222-2222-2222-2222-222222222222", "Mouse Sem Fio", "129.90", 80);
    private final Product headset = product("44444444-4444-4444-4444-444444444444", "Headset USB", "279.50", 0);
    private final Map<UUID, Product> stock = Stream.of(keyboard, mouse, headset)
            .collect(Collectors.toMap(Product::getId, Function.identity()));

    @Test
    void takesEveryItemAtTheCatalogPrice() {
        List<RequestedItem> requested = List.of(item(keyboard, 2), item(mouse, 1));

        assertThat(Rejection.of(requested, stock)).isEmpty();
        Reservation reservation = Reservation.of(ORDER_ID, requested, stock);

        assertThat(reservation.getOrderId()).isEqualTo(ORDER_ID);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(reservation.getReleasedAt()).isNull();
        assertThat(reservation.getItems()).containsExactly(
                new ReservedItem(keyboard.getId(), "Teclado Mecânico", 2, new BigDecimal("349.90")),
                new ReservedItem(mouse.getId(), "Mouse Sem Fio", 1, new BigDecimal("129.90")));
        assertThat(reservation.totalAmount()).isEqualTo(new BigDecimal("829.70"));
        assertThat(keyboard.getAvailableQuantity()).isEqualTo(48);
        assertThat(mouse.getAvailableQuantity()).isEqualTo(79);
    }

    @Test
    void theLastUnitCanBeTaken() {
        keyboard.setAvailableQuantity(1);

        assertThat(Rejection.of(List.of(item(keyboard, 1)), stock)).isEmpty();
        Reservation.of(ORDER_ID, List.of(item(keyboard, 1)), stock);

        assertThat(keyboard.getAvailableQuantity()).isZero();
    }

    @Test
    void oneItemWithoutStockIsEnoughToRejectTheOrder() {
        Optional<Rejection> rejection = Rejection.of(List.of(item(keyboard, 2), item(headset, 1)), stock);

        assertThat(rejection).contains(new Rejection(RejectionReason.INSUFFICIENT_STOCK,
                List.of(new UnavailableItem(headset.getId(), 1, 0))));
    }

    @Test
    void rejectionListsEveryItemThatIsShort() {
        Optional<Rejection> rejection = Rejection.of(
                List.of(item(keyboard, 51), item(mouse, 80), item(headset, 3)), stock);

        assertThat(rejection).contains(new Rejection(RejectionReason.INSUFFICIENT_STOCK, List.of(
                new UnavailableItem(keyboard.getId(), 51, 50),
                new UnavailableItem(headset.getId(), 3, 0))));
    }

    @Test
    void unknownProductIsReportedInsteadOfTheShortOnes() {
        UUID unknown = UUID.randomUUID();

        Optional<Rejection> rejection = Rejection.of(
                List.of(item(headset, 1), new RequestedItem(unknown, 4)), stock);

        assertThat(rejection).contains(new Rejection(RejectionReason.PRODUCT_NOT_FOUND,
                List.of(new UnavailableItem(unknown, 4, 0))));
    }

    @Test
    void checkingAnOrderTakesNothing() {
        Rejection.of(List.of(item(keyboard, 2), item(headset, 1)), stock);

        assertThat(keyboard.getAvailableQuantity()).isEqualTo(50);
    }

    @Test
    void sameProductOnTwoLinesIsCheckedAsOne() {
        List<RequestedItem> requested = RequestedItem.perProduct(
                List.of(item(keyboard, 30), item(mouse, 1), item(keyboard, 30)));

        assertThat(requested).containsExactly(item(keyboard, 60), item(mouse, 1));
        assertThat(Rejection.of(requested, stock)).contains(new Rejection(RejectionReason.INSUFFICIENT_STOCK,
                List.of(new UnavailableItem(keyboard.getId(), 60, 50))));
    }

    @Test
    void releaseGivesTheStockBack() {
        Reservation reservation = Reservation.of(ORDER_ID, List.of(item(keyboard, 2), item(mouse, 1)), stock);

        reservation.release(stock);

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(reservation.getReleasedAt()).isAfterOrEqualTo(reservation.getCreatedAt());
        assertThat(keyboard.getAvailableQuantity()).isEqualTo(50);
        assertThat(mouse.getAvailableQuantity()).isEqualTo(80);
    }

    @Test
    void releasingTwiceGivesTheStockBackOnce() {
        Reservation reservation = Reservation.of(ORDER_ID, List.of(item(keyboard, 2)), stock);
        reservation.release(stock);
        var releasedAt = reservation.getReleasedAt();

        reservation.release(stock);

        assertThat(keyboard.getAvailableQuantity()).isEqualTo(50);
        assertThat(reservation.getReleasedAt()).isEqualTo(releasedAt);
    }

    @Test
    void productRefusesToGoBelowZero() {
        assertThatThrownBy(() -> headset.take(1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> keyboard.setAvailableQuantity(-1)).isInstanceOf(IllegalArgumentException.class);

        assertThat(headset.getAvailableQuantity()).isZero();
        assertThat(keyboard.getAvailableQuantity()).isEqualTo(50);
    }

    private static RequestedItem item(Product product, int quantity) {
        return new RequestedItem(product.getId(), quantity);
    }

    private static Product product(String id, String name, String price, int availableQuantity) {
        Product product = new Product(name, null, new BigDecimal(price), availableQuantity);
        ReflectionTestUtils.setField(product, "id", UUID.fromString(id));
        return product;
    }
}
