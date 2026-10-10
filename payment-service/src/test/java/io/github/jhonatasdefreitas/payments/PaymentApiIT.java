package io.github.jhonatasdefreitas.payments;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jhonatasdefreitas.payments.api.PaymentResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class PaymentApiIT extends IntegrationTestBase {

    @Test
    void storedPaymentIsReadBackWithEveryField() {
        UUID orderId = UUID.randomUUID();
        otherServices.stockReserved(orderId, "7499.00");
        awaitPublished(orderId, "PaymentRefused");

        client.get().uri("/payments/{orderId}", orderId)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isNotEmpty()
                .jsonPath("$.orderId").isEqualTo(orderId.toString())
                .jsonPath("$.amount").isEqualTo(7499.00)
                .jsonPath("$.status").isEqualTo("REFUSED")
                .jsonPath("$.reason").isEqualTo("LIMIT_EXCEEDED")
                .jsonPath("$.createdAt").isNotEmpty();
    }

    @Test
    void listsNewestFirstInPages() {
        PaymentResponse first = charge("100.00");
        PaymentResponse second = charge("200.00");
        PaymentResponse third = charge("300.00");

        // Other tests leave payments in the same database, so the totals are only a lower bound.
        // Nothing else is charged while this test runs, so these three are the newest.
        client.get().uri("/payments?size=2")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.page").isEqualTo(0)
                .jsonPath("$.size").isEqualTo(2)
                .jsonPath("$.totalElements").value(total -> assertThat((Integer) total).isGreaterThanOrEqualTo(3))
                .jsonPath("$.totalPages").value(pages -> assertThat((Integer) pages).isGreaterThanOrEqualTo(2))
                .jsonPath("$.content.length()").isEqualTo(2)
                .jsonPath("$.content[0].id").isEqualTo(third.id().toString())
                .jsonPath("$.content[0].orderId").isEqualTo(third.orderId().toString())
                .jsonPath("$.content[0].amount").isEqualTo(300.00)
                .jsonPath("$.content[0].status").isEqualTo("APPROVED")
                .jsonPath("$.content[1].id").isEqualTo(second.id().toString());

        client.get().uri("/payments?size=2&page=1")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.page").isEqualTo(1)
                .jsonPath("$.content[0].id").isEqualTo(first.id().toString());

        client.get().uri("/payments")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.size").isEqualTo(20)
                .jsonPath("$.content[0].id").isEqualTo(third.id().toString());
    }

    @Test
    void orderWithoutPaymentIsANotFoundProblem() {
        UUID unknown = UUID.randomUUID();

        client.get().uri("/payments/{orderId}", unknown)
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.type").isEqualTo("about:blank")
                .jsonPath("$.title").isEqualTo("Not Found")
                .jsonPath("$.status").isEqualTo(404)
                .jsonPath("$.detail").isEqualTo("Payment for order " + unknown + " not found")
                .jsonPath("$.instance").isEqualTo("/payments/" + unknown);
    }

    @Test
    void pageSizeOutOfRangeIsRefusedAsAProblemWithTheField() {
        client.get().uri("/payments?size=0")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.type").isEqualTo("about:blank")
                .jsonPath("$.title").isEqualTo("Bad Request")
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.detail").isNotEmpty()
                .jsonPath("$.instance").isEqualTo("/payments")
                .jsonPath("$.errors[0].field").isEqualTo("size")
                .jsonPath("$.errors[0].message").isNotEmpty();
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

    // Waits for each payment before asking for the next, so their order in time is known.
    private PaymentResponse charge(String amount) {
        UUID orderId = UUID.randomUUID();
        otherServices.stockReserved(orderId, amount);
        awaitPublished(orderId, "PaymentApproved");
        return getPayment(orderId);
    }
}
