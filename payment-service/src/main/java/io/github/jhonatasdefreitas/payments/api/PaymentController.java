package io.github.jhonatasdefreitas.payments.api;

import io.github.jhonatasdefreitas.payments.domain.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payments")
@Tag(name = "Payments")
class PaymentController {

    private final PaymentService payments;

    PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    @GetMapping("/{orderId}")
    @Operation(summary = "Get the payment of an order",
            description = "Answers 404 when the order was not charged, which includes an order that never got its stock.")
    PaymentResponse get(@PathVariable UUID orderId) {
        return PaymentResponse.from(payments.get(orderId));
    }

    @GetMapping
    @Operation(summary = "List payments, newest first")
    PageResponse<PaymentResponse> list(@RequestParam(defaultValue = "0") @Min(0) int page,
                                       @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return PageResponse.from(payments.list(page, size).map(PaymentResponse::from));
    }
}
