package io.github.jhonatasdefreitas.payments.domain;

import io.github.jhonatasdefreitas.payments.persistence.PaymentRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt", "id");

    private final PaymentRepository payments;
    private final ApplicationEventPublisher events;
    private final BigDecimal approvalLimit;

    public PaymentService(PaymentRepository payments, ApplicationEventPublisher events,
                          @Value("${app.payment.approval-limit}") BigDecimal approvalLimit) {
        this.payments = payments;
        this.events = events;
        this.approvalLimit = approvalLimit;
    }

    /** Charges the order once. A second request for the same order changes nothing. */
    public void charge(UUID orderId, BigDecimal amount) {
        if (payments.existsByOrderId(orderId)) {
            log.info("Order already has a payment, nothing to charge");
            return;
        }
        Payment payment = payments.save(Payment.charge(orderId, amount, approvalLimit));
        if (payment.isApproved()) {
            log.info("Payment of {} approved", payment.getAmount());
        } else {
            log.info("Payment of {} refused, {}: the limit is {}", payment.getAmount(), payment.getReason(),
                    approvalLimit);
        }
        events.publishEvent(new PaymentDecided(payment));
    }

    @Transactional(readOnly = true)
    public Payment get(UUID orderId) {
        return payments.findByOrderId(orderId).orElseThrow(() -> new PaymentNotFoundException(orderId));
    }

    @Transactional(readOnly = true)
    public Page<Payment> list(int page, int size) {
        return payments.findAll(PageRequest.of(page, size, NEWEST_FIRST));
    }
}
