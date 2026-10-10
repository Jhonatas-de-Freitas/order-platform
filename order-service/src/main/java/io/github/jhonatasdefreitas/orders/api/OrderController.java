package io.github.jhonatasdefreitas.orders.api;

import io.github.jhonatasdefreitas.orders.domain.Order;
import io.github.jhonatasdefreitas.orders.domain.OrderItem;
import io.github.jhonatasdefreitas.orders.domain.OrderService;
import io.github.jhonatasdefreitas.orders.domain.OrderStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/orders")
@Tag(name = "Orders")
class OrderController {

    private final OrderService orders;
    private final OrderStream stream;

    OrderController(OrderService orders, OrderStream stream) {
        this.orders = orders;
        this.stream = stream;
    }

    @PostMapping
    @Operation(summary = "Place an order",
            description = "Answers 202 with the order still PENDING. Stock and payment are settled by events.")
    ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest request) {
        List<OrderItem> items = request.items().stream()
                .map(item -> OrderItem.requested(item.productId(), item.quantity()))
                .toList();
        Order order = orders.place(request.customerId(), request.customerName(), request.customerEmail(), items);
        return ResponseEntity.accepted()
                .location(URI.create("/orders/" + order.getId()))
                .body(OrderResponse.from(order));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get an order with its items and status history")
    OrderResponse get(@PathVariable UUID id) {
        return OrderResponse.from(orders.get(id));
    }

    @GetMapping
    @Operation(summary = "List orders, newest first")
    PageResponse<OrderSummary> list(@RequestParam(required = false) OrderStatus status,
                                    @RequestParam(required = false) String customerId,
                                    @RequestParam(defaultValue = "0") @Min(0) int page,
                                    @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return PageResponse.from(orders.search(status, customerId, page, size).map(OrderSummary::from));
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Follow status changes as server-sent events",
            description = "Sends an order-updated event with the order summary at each status change.")
    SseEmitter stream() {
        return stream.subscribe();
    }
}
