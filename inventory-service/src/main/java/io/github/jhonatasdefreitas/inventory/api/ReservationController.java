package io.github.jhonatasdefreitas.inventory.api;

import io.github.jhonatasdefreitas.inventory.domain.ReservationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/reservations")
@Tag(name = "Reservations")
class ReservationController {

    private final ReservationService reservations;

    ReservationController(ReservationService reservations) {
        this.reservations = reservations;
    }

    @GetMapping("/{orderId}")
    @Operation(summary = "Get the stock reserved for an order",
            description = "Answers 404 when nothing was reserved, which includes an order rejected for lack of stock.")
    ReservationResponse get(@PathVariable UUID orderId) {
        return ReservationResponse.from(reservations.get(orderId));
    }
}
