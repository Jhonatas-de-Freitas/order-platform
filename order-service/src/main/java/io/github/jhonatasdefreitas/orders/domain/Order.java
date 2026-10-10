package io.github.jhonatasdefreitas.orders.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String customerId;
    private String customerName;
    private String customerEmail;

    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    private BigDecimal totalAmount;

    @Enumerated(EnumType.STRING)
    private CancellationReason cancellationReason;

    private String cancellationDetail;

    private boolean paymentApproved;

    // Kept on the order so the listing never has to load the items.
    private int itemCount;

    private Instant createdAt;
    private Instant updatedAt;

    @ElementCollection
    @CollectionTable(name = "order_items", joinColumns = @JoinColumn(name = "order_id"))
    @OrderColumn(name = "seq")
    private List<OrderItem> items = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "order_history", joinColumns = @JoinColumn(name = "order_id"))
    @OrderColumn(name = "seq")
    private List<StatusChange> history = new ArrayList<>();

    protected Order() {
    }

    public static Order place(String customerId, String customerName, String customerEmail, List<OrderItem> items) {
        Order order = new Order();
        order.customerId = customerId;
        order.customerName = customerName;
        order.customerEmail = customerEmail;
        order.items.addAll(items);
        order.itemCount = items.size();
        order.moveTo(OrderStatus.PENDING, "Pedido recebido");
        order.createdAt = order.updatedAt;
        return order;
    }

    public void reserveStock(List<ReservedItem> reservedItems, BigDecimal totalAmount) {
        if (status != OrderStatus.PENDING) {
            return;
        }
        Map<UUID, ReservedItem> reserved = reservedItems.stream()
                .collect(Collectors.toMap(ReservedItem::productId, Function.identity(), (first, repeated) -> first));
        items.replaceAll(item -> {
            ReservedItem match = reserved.get(item.productId());
            return match == null ? item : item.priced(match.productName(), match.unitPrice());
        });
        this.totalAmount = totalAmount.setScale(2, RoundingMode.HALF_UP);
        moveTo(OrderStatus.STOCK_RESERVED, "Estoque reservado");
        if (paymentApproved) {
            moveTo(OrderStatus.CONFIRMED, "Pagamento aprovado");
        }
    }

    public void approvePayment() {
        if (status.isFinal()) {
            return;
        }
        paymentApproved = true;
        // While PENDING the approval has overtaken StockReserved, which carries the prices and the total.
        // The order only remembers it here and confirms when the stock event arrives.
        if (status == OrderStatus.STOCK_RESERVED) {
            moveTo(OrderStatus.CONFIRMED, "Pagamento aprovado");
        }
    }

    public void cancel(CancellationReason reason, String detail) {
        if (status.isFinal()) {
            return;
        }
        cancellationReason = reason;
        cancellationDetail = detail;
        moveTo(OrderStatus.CANCELLED, detail);
    }

    private void moveTo(OrderStatus newStatus, String detail) {
        status = newStatus;
        // Milliseconds are what the event contract shows and what survives a round trip to the database.
        updatedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        history.add(new StatusChange(newStatus, updatedAt, detail));
    }

    public UUID getId() {
        return id;
    }

    public String getCustomerId() {
        return customerId;
    }

    public String getCustomerName() {
        return customerName;
    }

    public String getCustomerEmail() {
        return customerEmail;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public CancellationReason getCancellationReason() {
        return cancellationReason;
    }

    public String getCancellationDetail() {
        return cancellationDetail;
    }

    public int getItemCount() {
        return itemCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public List<OrderItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    public List<StatusChange> getHistory() {
        return Collections.unmodifiableList(history);
    }
}
