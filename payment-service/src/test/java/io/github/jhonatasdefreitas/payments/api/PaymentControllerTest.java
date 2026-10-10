package io.github.jhonatasdefreitas.payments.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.jhonatasdefreitas.payments.domain.Payment;
import io.github.jhonatasdefreitas.payments.domain.PaymentNotFoundException;
import io.github.jhonatasdefreitas.payments.domain.PaymentService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PaymentController.class)
class PaymentControllerTest {

    private static final BigDecimal LIMIT = new BigDecimal("5000.00");
    private static final MediaType PROBLEM = MediaType.APPLICATION_PROBLEM_JSON;

    @Autowired
    MockMvc mvc;

    @MockitoBean
    PaymentService payments;

    @Test
    void showsTheApprovedPaymentOfAnOrder() throws Exception {
        Payment payment = payment("699.8");
        when(payments.get(payment.getOrderId())).thenReturn(payment);

        mvc.perform(get("/payments/{orderId}", payment.getOrderId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(payment.getId().toString()))
                .andExpect(jsonPath("$.orderId").value(payment.getOrderId().toString()))
                .andExpect(jsonPath("$.amount").value(699.80))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.reason").value((Object) null))
                .andExpect(jsonPath("$.createdAt").value(payment.getCreatedAt().toString()))
                .andExpect(content().string(containsString("\"amount\":699.80")));
    }

    @Test
    void showsWhyAPaymentWasRefused() throws Exception {
        Payment payment = payment("7499.00");
        when(payments.get(payment.getOrderId())).thenReturn(payment);

        mvc.perform(get("/payments/{orderId}", payment.getOrderId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(7499.00))
                .andExpect(jsonPath("$.status").value("REFUSED"))
                .andExpect(jsonPath("$.reason").value("LIMIT_EXCEEDED"));
    }

    @Test
    void answersNotFoundForAnOrderWithoutPayment() throws Exception {
        UUID orderId = UUID.randomUUID();
        when(payments.get(orderId)).thenThrow(new PaymentNotFoundException(orderId));

        mvc.perform(get("/payments/{orderId}", orderId))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Payment for order " + orderId + " not found"))
                .andExpect(jsonPath("$.instance").value("/payments/" + orderId));
    }

    @Test
    void answersBadRequestForOrderIdThatIsNotAUuid() throws Exception {
        mvc.perform(get("/payments/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM));
    }

    @Test
    void listsWithDefaultPaging() throws Exception {
        Payment refused = payment("7499.00");
        Payment approved = payment("699.80");
        when(payments.list(0, 20)).thenReturn(new PageImpl<>(List.of(refused, approved), PageRequest.of(0, 20), 2));

        mvc.perform(get("/payments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[0].orderId").value(refused.getOrderId().toString()))
                .andExpect(jsonPath("$.content[0].status").value("REFUSED"))
                .andExpect(jsonPath("$.content[1].orderId").value(approved.getOrderId().toString()))
                .andExpect(jsonPath("$.content[1].amount").value(699.80));
    }

    @Test
    void listsThePageThatWasAskedFor() throws Exception {
        when(payments.list(3, 5)).thenReturn(new PageImpl<>(List.of(), PageRequest.of(3, 5), 12));

        mvc.perform(get("/payments").param("page", "3").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(3))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.totalElements").value(12))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.content", hasSize(0)));
    }

    @Test
    void treatsEmptyPagingParametersAsTheDefault() throws Exception {
        when(payments.list(0, 20)).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mvc.perform(get("/payments?page=&size="))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(20));
    }

    @Test
    void refusesPagingOutOfRangeNamingTheField() throws Exception {
        mvc.perform(get("/payments").param("size", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.instance").value("/payments"))
                .andExpect(jsonPath("$.errors[0].field").value("size"))
                .andExpect(jsonPath("$.errors[0].message").isNotEmpty());

        mvc.perform(get("/payments").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("page"));

        verifyNoInteractions(payments);
    }

    @Test
    void answersProblemForMethodNotAllowed() throws Exception {
        mvc.perform(post("/payments"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Method Not Allowed"))
                .andExpect(jsonPath("$.instance").value("/payments"));
    }

    @Test
    void hidesTheCauseOfAnUnexpectedError() throws Exception {
        when(payments.get(any())).thenThrow(new IllegalStateException("connection refused to 10.0.0.7"));

        mvc.perform(get("/payments/{orderId}", UUID.randomUUID()))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.detail").value("Something went wrong on our side. Try again in a moment."));
    }

    private static Payment payment(String amount) {
        Payment payment = Payment.charge(UUID.randomUUID(), new BigDecimal(amount), LIMIT);
        ReflectionTestUtils.setField(payment, "id", UUID.randomUUID());
        return payment;
    }
}
