package io.github.jhonatasdefreitas.orders;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class OrderStreamIT extends IntegrationTestBase {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final Queue<String> lines = new ConcurrentLinkedQueue<>();
    private HttpResponse<Stream<String>> stream;

    @BeforeEach
    void openStream() throws Exception {
        URI uri = URI.create("http://localhost:" + port + "/orders/stream");
        stream = http.send(HttpRequest.newBuilder(uri).build(), BodyHandlers.ofLines());
        Thread.ofVirtual().start(() -> stream.body().forEach(lines::add));
    }

    @AfterEach
    void closeStream() {
        stream.body().close();
        http.shutdownNow();
    }

    @Test
    void streamsEveryStatusChangeOfAnOrder() {
        assertThat(stream.statusCode()).isEqualTo(200);
        assertThat(stream.headers().firstValue("Content-Type")).hasValueSatisfying(
                contentType -> assertThat(contentType).startsWith("text/event-stream"));

        UUID orderId = placeKeyboardOrder().id();
        otherServices.stockReserved(orderId);
        eventually().untilAsserted(() -> assertThat(updatesOf(orderId)).hasSize(2));
        otherServices.paymentApproved(orderId);
        eventually().untilAsserted(() -> assertThat(updatesOf(orderId)).hasSize(3));

        List<JsonNode> updates = updatesOf(orderId);
        assertThat(updates).extracting(update -> update.get("status").asString())
                .containsExactly("PENDING", "STOCK_RESERVED", "CONFIRMED");
        assertThat(updates.getFirst().get("totalAmount").isNull()).isTrue();
        JsonNode last = updates.getLast();
        assertThat(last.propertyNames()).containsExactlyInAnyOrder("id", "customerId", "customerName",
                "status", "totalAmount", "itemCount", "createdAt", "updatedAt");
        assertThat(last.get("customerName").asString()).isEqualTo("Ana Souza");
        assertThat(last.get("totalAmount").decimalValue()).isEqualByComparingTo("699.80");
        assertThat(last.get("itemCount").asInt()).isEqualTo(1);
    }

    @Test
    void streamsBothChangesWhenOneEventMovesTheOrderTwice() {
        UUID orderId = placeKeyboardOrder().id();
        awaitProcessed(otherServices.paymentApproved(orderId));

        otherServices.stockReserved(orderId);

        eventually().untilAsserted(() -> assertThat(updatesOf(orderId))
                .extracting(update -> update.get("status").asString())
                .containsExactly("PENDING", "STOCK_RESERVED", "CONFIRMED"));
    }

    @Test
    void keepsTheConnectionAliveWithComments() {
        eventually().untilAsserted(() -> assertThat(lines).contains(":keep-alive"));
    }

    // An SSE message is an "event:" line followed by its "data:" line.
    private List<JsonNode> updatesOf(UUID orderId) {
        List<String> received = List.copyOf(lines);
        return Stream.iterate(0, i -> i < received.size() - 1, i -> i + 1)
                .filter(i -> received.get(i).equals("event:order-updated") && received.get(i + 1).startsWith("data:"))
                .map(i -> JSON.readTree(received.get(i + 1).substring("data:".length())))
                .filter(update -> update.get("id").asString().equals(orderId.toString()))
                .toList();
    }
}
