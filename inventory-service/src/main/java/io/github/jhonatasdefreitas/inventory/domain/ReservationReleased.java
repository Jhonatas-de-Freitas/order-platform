package io.github.jhonatasdefreitas.inventory.domain;

/** Published inside the transaction that gave the stock back. */
public record ReservationReleased(Reservation reservation) {
}
