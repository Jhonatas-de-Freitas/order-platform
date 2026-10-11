package io.github.jhonatasdefreitas.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;

import io.github.jhonatasdefreitas.inventory.api.ProductResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;

class ProductApiIT extends IntegrationTestBase {

    @Autowired
    TransactionTemplate transactions;

    @Test
    void startsWithTheSixProductsOfTheDemo() {
        List<UUID> seeded = Stream.of('1', '2', '3', '4', '5', '6').map(ProductApiIT::seeded).toList();

        // No test touches these products: each one creates what it needs.
        assertThat(listProducts())
                .filteredOn(product -> seeded.contains(product.id()))
                .extracting(ProductResponse::id, ProductResponse::name, ProductResponse::price,
                        ProductResponse::availableQuantity)
                .containsExactlyInAnyOrder(
                        tuple(seeded('1'), "Teclado Mecânico", new BigDecimal("349.90"), 50),
                        tuple(seeded('2'), "Mouse Sem Fio", new BigDecimal("129.90"), 80),
                        tuple(seeded('3'), "Monitor 27\" 4K", new BigDecimal("2199.00"), 15),
                        tuple(seeded('4'), "Headset USB", new BigDecimal("279.50"), 0),
                        tuple(seeded('5'), "Notebook Pro 16", new BigDecimal("7499.00"), 10),
                        tuple(seeded('6'), "Webcam Full HD", new BigDecimal("189.00"), 30));
    }

    @Test
    void seededProductCanBeReadById() {
        client.get().uri("/products/11111111-1111-1111-1111-111111111111")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo("11111111-1111-1111-1111-111111111111")
                .jsonPath("$.name").isEqualTo("Teclado Mecânico")
                .jsonPath("$.description").isNotEmpty()
                .jsonPath("$.price").isEqualTo(349.90)
                .jsonPath("$.availableQuantity").isEqualTo(50);
    }

    @Test
    void listsTheCatalogOrderedByName() {
        ProductResponse stand = createProduct("Suporte de Monitor", "149.00", 1);
        ProductResponse adapter = createProduct("Adaptador USB-C", "89.00", 1);

        List<UUID> ids = listProducts().stream().map(ProductResponse::id).toList();

        assertThat(ids.indexOf(adapter.id())).isLessThan(ids.indexOf(stand.id()));
    }

    @Test
    void createdProductCanBeReadBack() {
        ProductResponse created = client.post().uri("/products")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"name":"Mousepad","description":"Mousepad de tecido","price":59.9,"availableQuantity":25}
                        """)
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().valueMatches("Location", "/products/[0-9a-f-]{36}")
                .expectBody(ProductResponse.class)
                .returnResult().getResponseBody();

        assertThat(created.name()).isEqualTo("Mousepad");
        assertThat(created.description()).isEqualTo("Mousepad de tecido");
        assertThat(created.price()).isEqualTo(new BigDecimal("59.90"));
        assertThat(created.availableQuantity()).isEqualTo(25);
        assertThat(client.get().uri("/products/{id}", created.id())
                .exchange()
                .expectStatus().isOk()
                .expectBody(ProductResponse.class)
                .returnResult().getResponseBody()).isEqualTo(created);
    }

    @Test
    void settingTheStockReplacesTheBalance() {
        ProductResponse product = createProduct("Cabo HDMI", "39.90", 3);

        client.put().uri("/products/{id}/stock", product.id())
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"availableQuantity":40}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(product.id().toString())
                .jsonPath("$.name").isEqualTo("Cabo HDMI")
                .jsonPath("$.availableQuantity").isEqualTo(40);

        assertThat(stockOf(product.id())).isEqualTo(40);
    }

    @Test
    void settingTheStockWaitsForAReservationThatHoldsTheProduct() throws Exception {
        ProductResponse product = createProduct("Cabo HDMI", "39.90", 50);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);

        // Stands in for a reservation in the middle of its transaction: the row is locked and 48 is not committed.
        CompletableFuture<Void> reservation = CompletableFuture.runAsync(() -> transactions.executeWithoutResult(tx -> {
            jdbc.sql("update products set available_quantity = available_quantity - 2 where id = ?")
                    .param(product.id())
                    .update();
            locked.countDown();
            awaitQuietly(commit);
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Integer> setStock = CompletableFuture.supplyAsync(() -> client.put()
                .uri("/products/{id}/stock", product.id())
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"availableQuantity":50}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody(ProductResponse.class)
                .returnResult().getResponseBody().availableQuantity());
        // Either it answered already, without waiting for the lock, or it is waiting for it.
        eventually().until(() -> setStock.isDone() || sessionsWaitingForALock() > 0);
        commit.countDown();
        reservation.get(10, TimeUnit.SECONDS);

        assertThat(setStock.get(10, TimeUnit.SECONDS)).isEqualTo(50);
        assertThat(stockOf(product.id())).isEqualTo(50);
    }

    @Test
    void invalidProductIsRefusedAsAProblemWithTheFields() {
        client.post().uri("/products")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"name":"","price":-1,"availableQuantity":-5}
                        """)
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.type").isEqualTo("about:blank")
                .jsonPath("$.title").isEqualTo("Bad Request")
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.detail").isNotEmpty()
                .jsonPath("$.instance").isEqualTo("/products")
                .jsonPath("$.errors[*].field").value(fields -> assertThat(fields).asInstanceOf(LIST)
                        .containsExactlyInAnyOrder("name", "price", "availableQuantity"))
                .jsonPath("$.errors[0].message").isNotEmpty();
    }

    @Test
    void unknownProductIsANotFoundProblem() {
        UUID unknown = UUID.randomUUID();

        client.get().uri("/products/{id}", unknown)
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.type").isEqualTo("about:blank")
                .jsonPath("$.title").isEqualTo("Not Found")
                .jsonPath("$.status").isEqualTo(404)
                .jsonPath("$.detail").isEqualTo("Product " + unknown + " not found")
                .jsonPath("$.instance").isEqualTo("/products/" + unknown);

        client.put().uri("/products/{id}/stock", unknown)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"availableQuantity":1}
                        """)
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void orderWithoutReservationIsANotFoundProblem() {
        UUID unknown = UUID.randomUUID();

        client.get().uri("/reservations/{orderId}", unknown)
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(404)
                .jsonPath("$.detail").isEqualTo("Reservation for order " + unknown + " not found")
                .jsonPath("$.instance").isEqualTo("/reservations/" + unknown);
    }

    private static UUID seeded(char digit) {
        return UUID.fromString("xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx".replace('x', digit));
    }

    private long sessionsWaitingForALock() {
        return jdbc.sql("select count(*) from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock'")
                .query(Long.class)
                .single();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private List<ProductResponse> listProducts() {
        return client.get().uri("/products")
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<ProductResponse>>() {
                })
                .returnResult().getResponseBody();
    }
}
