package io.github.jhonatasdefreitas.inventory.domain;

import io.github.jhonatasdefreitas.inventory.persistence.ProductRepository;
import io.github.jhonatasdefreitas.inventory.persistence.ReservationRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    private final ReservationRepository reservations;
    private final ProductRepository products;
    private final ApplicationEventPublisher events;

    public ReservationService(ReservationRepository reservations, ProductRepository products,
                              ApplicationEventPublisher events) {
        this.reservations = reservations;
        this.products = products;
        this.events = events;
    }

    /** Reserves every item of the order at the catalog price, or none of them. */
    public void reserve(UUID orderId, List<RequestedItem> requestedItems) {
        if (reservations.existsByOrderId(orderId)) {
            log.info("Order already has a reservation, nothing to reserve");
            return;
        }
        List<RequestedItem> requested = RequestedItem.perProduct(requestedItems);
        Map<UUID, Product> stock = lockProducts(requested.stream().map(RequestedItem::productId).toList());

        Optional<Rejection> rejection = Rejection.of(requested, stock);
        if (rejection.isPresent()) {
            log.info("Stock not reserved, {}: {}", rejection.get().reason(), rejection.get().unavailableItems());
            events.publishEvent(new ReservationRejected(orderId, rejection.get(),
                    Instant.now().truncatedTo(ChronoUnit.MILLIS)));
            return;
        }
        Reservation reservation = reservations.save(Reservation.of(orderId, requested, stock));
        log.info("Stock reserved for {} product(s), total {}", requested.size(), reservation.totalAmount());
        events.publishEvent(new ReservationCreated(reservation));
    }

    /** Gives the stock of a cancelled order back, if the order still holds any. */
    public void release(UUID orderId) {
        Optional<Reservation> held = reservations.findByOrderIdForUpdate(orderId).filter(Reservation::isActive);
        if (held.isEmpty()) {
            log.info("No active reservation, nothing to release");
            return;
        }
        Reservation reservation = held.get();
        reservation.release(lockProducts(reservation.getItems().stream().map(ReservedItem::productId).toList()));
        log.info("Stock of {} product(s) released", reservation.getItems().size());
        events.publishEvent(new ReservationReleased(reservation));
    }

    @Transactional(readOnly = true)
    public Reservation get(UUID orderId) {
        return reservations.findWithItemsByOrderId(orderId)
                .orElseThrow(() -> new ReservationNotFoundException(orderId));
    }

    private Map<UUID, Product> lockProducts(Collection<UUID> productIds) {
        return products.findAllByIdForUpdate(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
    }
}
