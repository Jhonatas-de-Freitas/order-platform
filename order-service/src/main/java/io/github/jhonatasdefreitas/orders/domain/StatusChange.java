package io.github.jhonatasdefreitas.orders.domain;

import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.time.Instant;

@Embeddable
public record StatusChange(@Enumerated(EnumType.STRING) OrderStatus status, Instant occurredAt, String detail) {
}
