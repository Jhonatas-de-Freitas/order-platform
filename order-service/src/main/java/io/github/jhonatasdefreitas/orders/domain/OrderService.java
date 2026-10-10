package io.github.jhonatasdefreitas.orders.domain;

import io.github.jhonatasdefreitas.orders.persistence.OrderRepository;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt", "id");

    private final OrderRepository orders;
    private final ApplicationEventPublisher events;

    public OrderService(OrderRepository orders, ApplicationEventPublisher events) {
        this.orders = orders;
        this.events = events;
    }

    public Order place(String customerId, String customerName, String customerEmail, List<OrderItem> items) {
        Order order = orders.save(Order.place(customerId, customerName, customerEmail, items));
        try (MDC.MDCCloseable ignored = MDC.putCloseable("orderId", order.getId().toString())) {
            log.info("Order received from customer {} with {} item(s)", customerId, items.size());
            events.publishEvent(new OrderStatusChanged(order, order.getHistory().getFirst()));
        }
        return order;
    }

    public void reserveStock(UUID orderId, List<ReservedItem> reservedItems, BigDecimal totalAmount) {
        change(orderId, "stock reserved", order -> order.reserveStock(reservedItems, totalAmount));
    }

    public void approvePayment(UUID orderId) {
        change(orderId, "payment approved", Order::approvePayment);
    }

    public void cancel(UUID orderId, CancellationReason reason, String detail) {
        change(orderId, "cancellation (" + reason + ")", order -> order.cancel(reason, detail));
    }

    @Transactional(readOnly = true)
    public Order get(UUID orderId) {
        return orders.findWithItemsAndHistoryById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    @Transactional(readOnly = true)
    public Page<Order> search(OrderStatus status, String customerId, int page, int size) {
        Specification<Order> filter = (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) {
                predicates.add(builder.equal(root.get("status"), status));
            }
            if (customerId != null && !customerId.isBlank()) {
                predicates.add(builder.equal(root.get("customerId"), customerId));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
        return orders.findAll(filter, PageRequest.of(page, size, NEWEST_FIRST));
    }

    // The history is the list of status changes, so whatever was appended is what has to be announced.
    private void change(UUID orderId, String trigger, Consumer<Order> action) {
        Order order = orders.findByIdForUpdate(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
        int known = order.getHistory().size();
        action.accept(order);
        List<StatusChange> changes = order.getHistory().subList(known, order.getHistory().size());
        if (changes.isEmpty()) {
            log.info("Order stays {} after {}", order.getStatus(), trigger);
        }
        for (StatusChange change : changes) {
            log.info("Order is now {} after {}", change.status(), trigger);
            events.publishEvent(new OrderStatusChanged(order, change));
        }
    }
}
