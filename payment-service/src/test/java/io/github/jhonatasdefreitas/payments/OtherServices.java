package io.github.jhonatasdefreitas.payments;

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
 * Stands in for the inventory service: publishes the events it would publish
 * and reads the topics as a consumer of the payment service would.
 */
class OtherServices {

    static final String INVENTORY_EVENTS = "inventory.events";
    static final String PAYMENT_EVENTS = "payments.events";
    static final String DEAD_LETTERS = "payment-service.dlt";

    private final KafkaTemplate<String, String> kafka;
    private final ConsumerFactory<String, String> consumers;

    OtherServices(KafkaTemplate<String, String> kafka, ConsumerFactory<String, String> consumers) {
        this.kafka = kafka;
        this.consumers = consumers;
    }

    UUID stockReserved(UUID orderId, String totalAmount) {
        return publish(INVENTORY_EVENTS, "StockReserved", orderId, stockReservedPayload(totalAmount));
    }

    /** One item that costs the whole total: the payment service only looks at the total. */
    static String stockReservedPayload(String totalAmount) {
        return """
                {"items":[{"productId":"11111111-1111-1111-1111-111111111111","productName":"Teclado Mecânico",
                           "quantity":1,"unitPrice":%s}],
                 "totalAmount":%s}
                """.formatted(totalAmount, totalAmount);
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
