package io.github.jhonatasdefreitas.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;

import io.github.jhonatasdefreitas.orders.api.OrderResponse;
import io.github.jhonatasdefreitas.orders.domain.OrderStatus;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

class OrderApiIT extends IntegrationTestBase {

    @Test
    void acceptsTheOrderAndAnswersBeforeAnythingIsSettled() {
        client.post().uri("/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"customerId":"c-1001","customerName":"Ana Souza","customerEmail":"ana@exemplo.com",
                         "items":[{"productId":"%s","quantity":2}]}
                        """.formatted(OtherServices.KEYBOARD))
                .exchange()
                .expectStatus().isAccepted()
                .expectHeader().valueMatches("Location", "/orders/[0-9a-f-]{36}")
                .expectBody()
                .jsonPath("$.id").isNotEmpty()
                .jsonPath("$.customerId").isEqualTo("c-1001")
                .jsonPath("$.customerName").isEqualTo("Ana Souza")
                .jsonPath("$.customerEmail").isEqualTo("ana@exemplo.com")
                .jsonPath("$.status").isEqualTo("PENDING")
                .jsonPath("$.totalAmount").isEmpty()
                .jsonPath("$.cancellationReason").isEmpty()
                .jsonPath("$.cancellationDetail").isEmpty()
                .jsonPath("$.items[0].productId").isEqualTo(OtherServices.KEYBOARD)
                .jsonPath("$.items[0].productName").isEmpty()
                .jsonPath("$.items[0].quantity").isEqualTo(2)
                .jsonPath("$.items[0].unitPrice").isEmpty()
                .jsonPath("$.history.length()").isEqualTo(1)
                .jsonPath("$.history[0].status").isEqualTo("PENDING")
                .jsonPath("$.history[0].detail").isEqualTo("Pedido recebido")
                .jsonPath("$.history[0].occurredAt").isNotEmpty()
                .jsonPath("$.createdAt").isNotEmpty()
                .jsonPath("$.updatedAt").isNotEmpty();
    }

    @Test
    void storedOrderCanBeReadBack() {
        OrderResponse placed = placeKeyboardOrder();

        assertThat(getOrder(placed.id())).isEqualTo(placed);
    }

    @Test
    void readsBackEveryItemAndEveryHistoryEntryOnce() {
        UUID orderId = client.post().uri("/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"customerId":"c-1001","customerName":"Ana Souza","customerEmail":"ana@exemplo.com",
                         "items":[{"productId":"%s","quantity":1},{"productId":"%s","quantity":3}]}
                        """.formatted(OtherServices.HEADSET, OtherServices.KEYBOARD))
                .exchange()
                .expectBody(OrderResponse.class)
                .returnResult().getResponseBody().id();
        otherServices.stockRejected(orderId);

        OrderResponse cancelled = awaitStatus(orderId, OrderStatus.CANCELLED);

        assertThat(cancelled.items()).extracting(OrderResponse.Item::productId, OrderResponse.Item::quantity)
                .containsExactly(
                        tuple(UUID.fromString(OtherServices.HEADSET), 1),
                        tuple(UUID.fromString(OtherServices.KEYBOARD), 3));
        assertThat(cancelled.history()).extracting(OrderResponse.HistoryEntry::status)
                .containsExactly(OrderStatus.PENDING, OrderStatus.CANCELLED);
    }

    @Test
    void publishesOrderCreatedThroughTheOutbox() {
        OrderResponse placed = placeKeyboardOrder();

        eventually().untilAsserted(() ->
                assertThat(otherServices.read(OtherServices.ORDER_EVENTS, placed.id())).hasSize(1));

        ConsumerRecord<String, String> record = otherServices.read(OtherServices.ORDER_EVENTS, placed.id()).getFirst();
        assertThat(record.key()).isEqualTo(placed.id().toString());
        assertThat(new String(record.headers().lastHeader("event-type").value(), StandardCharsets.UTF_8))
                .isEqualTo("OrderCreated");

        JsonNode event = JSON.readTree(record.value());
        assertThat(UUID.fromString(event.get("eventId").asString())).isNotNull();
        assertThat(event.get("type").asString()).isEqualTo("OrderCreated");
        assertThat(event.get("orderId").asString()).isEqualTo(placed.id().toString());
        assertThat(event.get("occurredAt").asString()).isEqualTo(placed.createdAt().toString());
        assertThat(event.get("payload")).isEqualTo(JSON.readTree("""
                {"customerId":"c-1001","customerName":"Ana Souza","customerEmail":"ana@exemplo.com",
                 "items":[{"productId":"%s","quantity":2}]}
                """.formatted(OtherServices.KEYBOARD)));

        eventually().untilAsserted(() -> assertThat(pendingInOutbox(placed.id())).isZero());
    }

    @Test
    void listsNewestFirstWithFiltersAndPaging() {
        String customer = "c-" + UUID.randomUUID();
        OrderResponse first = placeOrder(customer, OtherServices.KEYBOARD, 1);
        OrderResponse second = placeOrder(customer, OtherServices.KEYBOARD, 2);
        OrderResponse third = placeOrder(customer, OtherServices.HEADSET, 3);
        otherServices.stockRejected(third.id());
        awaitStatus(third.id(), OrderStatus.CANCELLED);

        client.get().uri("/orders?customerId={customer}&size=2", customer)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.page").isEqualTo(0)
                .jsonPath("$.size").isEqualTo(2)
                .jsonPath("$.totalElements").isEqualTo(3)
                .jsonPath("$.totalPages").isEqualTo(2)
                .jsonPath("$.content.length()").isEqualTo(2)
                .jsonPath("$.content[0].id").isEqualTo(third.id().toString())
                .jsonPath("$.content[0].status").isEqualTo("CANCELLED")
                .jsonPath("$.content[0].customerName").isEqualTo("Ana Souza")
                .jsonPath("$.content[0].itemCount").isEqualTo(1)
                .jsonPath("$.content[0].createdAt").isNotEmpty()
                .jsonPath("$.content[0].updatedAt").isNotEmpty()
                .jsonPath("$.content[1].id").isEqualTo(second.id().toString());

        client.get().uri("/orders?customerId={customer}&size=2&page=1", customer)
                .exchange()
                .expectBody()
                .jsonPath("$.page").isEqualTo(1)
                .jsonPath("$.content.length()").isEqualTo(1)
                .jsonPath("$.content[0].id").isEqualTo(first.id().toString());

        client.get().uri("/orders?customerId={customer}&status=PENDING", customer)
                .exchange()
                .expectBody()
                .jsonPath("$.size").isEqualTo(20)
                .jsonPath("$.totalElements").isEqualTo(2)
                .jsonPath("$.content[*].id").value(ids ->
                        assertThat(ids).isEqualTo(List.of(second.id().toString(), first.id().toString())));

        client.get().uri("/orders?status=CANCELLED&customerId={customer}", customer)
                .exchange()
                .expectBody()
                .jsonPath("$.totalElements").isEqualTo(1)
                .jsonPath("$.content[0].id").isEqualTo(third.id().toString());
    }

    @Test
    void invalidOrderIsRefusedAsAProblemWithTheFields() {
        client.post().uri("/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"customerId":"c-1001","customerName":"","customerEmail":"ana-at-exemplo",
                         "items":[{"productId":"%s","quantity":0}]}
                        """.formatted(OtherServices.KEYBOARD))
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.type").isEqualTo("about:blank")
                .jsonPath("$.title").isEqualTo("Bad Request")
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.detail").isNotEmpty()
                .jsonPath("$.instance").isEqualTo("/orders")
                .jsonPath("$.errors[*].field").value(fields -> assertThat(fields).asInstanceOf(LIST)
                        .containsExactlyInAnyOrder("customerName", "customerEmail", "items[0].quantity"))
                .jsonPath("$.errors[0].message").isNotEmpty();
    }

    @Test
    void unknownOrderIsANotFoundProblem() {
        UUID unknown = UUID.randomUUID();

        client.get().uri("/orders/{id}", unknown)
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.type").isEqualTo("about:blank")
                .jsonPath("$.title").isEqualTo("Not Found")
                .jsonPath("$.status").isEqualTo(404)
                .jsonPath("$.detail").isEqualTo("Order " + unknown + " not found")
                .jsonPath("$.instance").isEqualTo("/orders/" + unknown);
    }

    @Test
    void unknownPathIsAProblemToo() {
        client.get().uri("/nothing-here")
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(404)
                .jsonPath("$.instance").isEqualTo("/nothing-here");
    }
}
