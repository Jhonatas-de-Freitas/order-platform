package io.github.jhonatasdefreitas.inventory.messaging;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

public record EventEnvelope(UUID eventId, String type, UUID orderId, Instant occurredAt, JsonNode payload) {

    // Its own mapper, so tuning the JSON of the REST API never changes what goes on the wire.
    // Decimals are read as BigDecimal so money never passes through a double.
    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .build();

    public EventEnvelope {
        if (eventId == null || orderId == null || type == null || type.isBlank()) {
            throw new IllegalArgumentException("eventId, type and orderId are required");
        }
    }

    public static EventEnvelope of(String type, UUID orderId, Instant occurredAt, Object payload) {
        return new EventEnvelope(UUID.randomUUID(), type, orderId, occurredAt, JSON.valueToTree(payload));
    }

    public static EventEnvelope parse(String json) {
        EventEnvelope envelope;
        try {
            envelope = json == null ? null : JSON.readValue(json, EventEnvelope.class);
        } catch (JacksonException e) {
            throw new InvalidEventException("Not a valid event: " + e.getOriginalMessage(), e);
        }
        if (envelope == null) {
            throw new InvalidEventException("Not a valid event: empty message");
        }
        return envelope;
    }

    public <T> T payloadAs(Class<T> payloadType) {
        T value;
        try {
            value = payload == null ? null : JSON.treeToValue(payload, payloadType);
        } catch (JacksonException e) {
            throw new InvalidEventException("Not a valid " + type + " payload: " + e.getOriginalMessage(), e);
        }
        if (value == null) {
            throw new InvalidEventException("Not a valid " + type + " payload: it is missing");
        }
        return value;
    }

    public String toJson() {
        return JSON.writeValueAsString(this);
    }
}
