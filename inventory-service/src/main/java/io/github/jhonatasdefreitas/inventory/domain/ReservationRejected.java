package io.github.jhonatasdefreitas.inventory.domain;

import java.time.Instant;
import java.util.UUID;

/** Published when an order could not be reserved. Nothing is stored for it, so the event carries the order id. */
public record ReservationRejected(UUID orderId, Rejection rejection, Instant occurredAt) {
}
