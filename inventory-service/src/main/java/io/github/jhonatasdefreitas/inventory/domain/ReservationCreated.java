package io.github.jhonatasdefreitas.inventory.domain;

/** Published inside the transaction that took the stock. */
public record ReservationCreated(Reservation reservation) {
}
