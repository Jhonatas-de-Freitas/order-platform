package io.github.jhonatasdefreitas.inventory.messaging;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.apache.kafka.clients.producer.ProducerRecord;

@Entity
@Table(name = "outbox")
public class OutboxEvent {

    // Assigned by the database in insertion order, which is the order the publisher follows.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private UUID eventId;
    private String topic;
    private String messageKey;
    private String eventType;
    private String payload;
    private Instant createdAt;
    private Instant publishedAt;

    protected OutboxEvent() {
    }

    OutboxEvent(String topic, EventEnvelope envelope) {
        this.eventId = envelope.eventId();
        this.topic = topic;
        this.messageKey = envelope.orderId().toString();
        this.eventType = envelope.type();
        this.payload = envelope.toJson();
        this.createdAt = Instant.now();
    }

    ProducerRecord<String, String> toRecord() {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, messageKey, payload);
        record.headers().add("event-type", eventType.getBytes(StandardCharsets.UTF_8));
        return record;
    }

    void markPublished() {
        publishedAt = Instant.now();
    }

    String getMessageKey() {
        return messageKey;
    }

    String getEventType() {
        return eventType;
    }
}
