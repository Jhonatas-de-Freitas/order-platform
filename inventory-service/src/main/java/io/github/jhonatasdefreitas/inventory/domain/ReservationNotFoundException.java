package io.github.jhonatasdefreitas.inventory.domain;

import java.util.UUID;

public class ReservationNotFoundException extends RuntimeException {

    public ReservationNotFoundException(UUID orderId) {
        super("Reservation for order " + orderId + " not found");
    }
}
