package io.github.jhonatasdefreitas.orders;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Stands in for the inventory and payment services: publishes the events they would publish
 * and reads the topics as a consumer of the order service would.
 */
class OtherServices {

    static final String KEYBOARD = "11111111-1111-1111-1111-111111111111";
    static final String HEADSET = "44444444-4444-4444-4444-444444444444";

    static final String INVENTORY_EVENTS = "inventory.events";
    static final String PAYMENT_EVENTS = "payments.events";
    static final String ORDER_EVENTS = "orders.events";
    static final String DEAD_LETTERS = "order-service.dlt";

    private final KafkaTemplate<String, String> kafka;
    private final ConsumerFactory<String, String> consumers;

    OtherServices(KafkaTemplate<String, String> kafka, ConsumerFactory<String, String> consumers) {
        this.kafka = kafka;
        this.consumers = consumers;
    }

    /** Two keyboards at 349.90, the happy path of the contract. */
    UUID stockReserved(UUID orderId) {
        return publish(INVENTORY_EVENTS, "StockReserved", orderId, """
                {"items":[{"productId":"%s","productName":"Teclado Mecânico","quantity":2,"unitPrice":349.90}],
                 "totalAmount":699.80}
                """.formatted(KEYBOARD));
    }

    UUID stockRejected(UUID orderId) {
        return publish(INVENTORY_EVENTS, "StockRejected", orderId, """
                {"reason":"INSUFFICIENT_STOCK",
                 "unavailableItems":[{"productId":"%s","requestedQuantity":1,"availableQuantity":0}]}
                """.formatted(HEADSET));
    }

    UUID paymentApproved(UUID orderId) {
        return publish(PAYMENT_EVENTS, "PaymentApproved", orderId, """
                {"paymentId":"%s","amount":699.80}
                """.formatted(UUID.randomUUID()));
    }

    UUID paymentRefused(UUID orderId) {
        return publish(PAYMENT_EVENTS, "PaymentRefused", orderId, """
                {"paymentId":"%s","amount":7499.00,"reason":"LIMIT_EXCEEDED"}
                """.formatted(UUID.randomUUID()));
    }

    UUID publish(String topic, String type, UUID orderId, String payload) {
        UUID eventId = UUID.randomUUID();
        publish(topic, eventId, type, orderId, payload);
        return eventId;
    }

    /** With the event id chosen by the caller, to deliver the very same event more than once. */
    void publish(String topic, UUID eventId, String type, UUID orderId, String payload) {
        String envelope = """
                {"eventId":"%s","type":"%s","orderId":"%s","occurredAt":"%s","payload":%s}
                """.formatted(eventId, type, orderId, Instant.now(), payload);
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, orderId.toString(), envelope);
        record.headers().add("event-type", type.getBytes(StandardCharsets.UTF_8));
        kafka.send(record).join();
    }

    void publishRaw(String topic, String key, String value) {
        kafka.send(topic, key, value).join();
    }

    /** Everything on the topic so far for one key, in the order it was written. */
    List<ConsumerRecord<String, String>> read(String topic, Object key) {
        try (Consumer<String, String> consumer = consumers.createConsumer("test-" + UUID.randomUUID(), null)) {
            List<TopicPartition> partitions = consumer.partitionsFor(topic).stream()
                    .map(partition -> new TopicPartition(topic, partition.partition()))
                    .toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions);

            List<ConsumerRecord<String, String>> found = new ArrayList<>();
            while (partitions.stream().anyMatch(partition -> consumer.position(partition) < end.get(partition))) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(200))) {
                    if (key.toString().equals(record.key())) {
                        found.add(record);
                    }
                }
            }
            return found;
        }
    }
}
