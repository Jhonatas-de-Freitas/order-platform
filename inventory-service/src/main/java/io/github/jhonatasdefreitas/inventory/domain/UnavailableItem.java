package io.github.jhonatasdefreitas.inventory.domain;

import java.util.UUID;

public record UnavailableItem(UUID productId, int requestedQuantity, int availableQuantity) {
}
