package io.github.jhonatasdefreitas.orders.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
class EventConsumer {

    private final EventHandler handler;

    EventConsumer(EventHandler handler) {
        this.handler = handler;
    }

    // The value arrives as plain text and is parsed here, so a message that is not JSON fails like any
    // other listener error and follows the same path to the dead letter topic.
    @KafkaListener(topics = {Topics.INVENTORY, Topics.PAYMENTS})
    void onMessage(ConsumerRecord<String, String> record) {
        EventEnvelope event = EventEnvelope.parse(record.value());
        try (MDC.MDCCloseable ignored = MDC.putCloseable("orderId", event.orderId().toString())) {
            handler.handle(event);
        }
    }
}
