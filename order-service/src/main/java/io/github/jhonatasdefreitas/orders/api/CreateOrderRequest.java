package io.github.jhonatasdefreitas.orders.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record CreateOrderRequest(
        @NotBlank @Size(max = 64) String customerId,
        @NotBlank @Size(max = 120) String customerName,
        @NotBlank @Email @Size(max = 254) String customerEmail,
        @NotNull @Size(min = 1, max = 20) @UniqueProducts List<@NotNull @Valid Item> items) {

    public record Item(
            @NotNull UUID productId,
            @NotNull @Min(1) @Max(100) Integer quantity) {
    }
}
