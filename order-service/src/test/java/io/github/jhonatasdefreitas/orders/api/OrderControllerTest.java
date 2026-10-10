package io.github.jhonatasdefreitas.orders.api;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.jhonatasdefreitas.orders.domain.Order;
import io.github.jhonatasdefreitas.orders.domain.OrderItem;
import io.github.jhonatasdefreitas.orders.domain.OrderNotFoundException;
import io.github.jhonatasdefreitas.orders.domain.OrderService;
import io.github.jhonatasdefreitas.orders.domain.OrderStatus;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@WebMvcTest(OrderController.class)
@Import(OrderStream.class)
class OrderControllerTest {

    private static final String KEYBOARD = "11111111-1111-1111-1111-111111111111";
    private static final String MOUSE = "22222222-2222-2222-2222-222222222222";
    private static final MediaType PROBLEM = MediaType.APPLICATION_PROBLEM_JSON;

    @Autowired
    MockMvc mvc;

    @MockitoBean
    OrderService orders;

    @Test
    void acceptsAnOrderAndPointsToIt() throws Exception {
        Order placed = Order.place("c-1001", "Ana Souza", "ana@exemplo.com",
                List.of(OrderItem.requested(UUID.fromString(KEYBOARD), 2)));
        ReflectionTestUtils.setField(placed, "id", UUID.randomUUID());
        when(orders.place(any(), any(), any(), anyList())).thenReturn(placed);

        postOrder("""
                {"customerId":"c-1001","customerName":"Ana Souza","customerEmail":"ana@exemplo.com",
                 "items":[{"productId":"%s","quantity":2}]}
                """.formatted(KEYBOARD))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/orders/" + placed.getId()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.customerEmail").value("ana@exemplo.com"))
                .andExpect(jsonPath("$.totalAmount").value((Object) null))
                .andExpect(jsonPath("$.cancellationReason").value((Object) null))
                .andExpect(jsonPath("$.items[0].productId").value(KEYBOARD))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.items[0].unitPrice").value((Object) null))
                .andExpect(jsonPath("$.history[0].status").value("PENDING"))
                .andExpect(jsonPath("$.history[0].detail").value("Pedido recebido"));

        verify(orders).place("c-1001", "Ana Souza", "ana@exemplo.com",
                List.of(OrderItem.requested(UUID.fromString(KEYBOARD), 2)));
    }

    @Test
    void refusesMissingCustomerDataNamingEachField() throws Exception {
        postOrder("""
                {"customerId":" ","customerEmail":"not-an-email","items":[{"productId":"%s","quantity":1}]}
                """.formatted(KEYBOARD))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").exists())
                .andExpect(jsonPath("$.instance").value("/orders"))
                .andExpect(jsonPath("$.errors[*].field")
                        .value(containsInAnyOrder("customerId", "customerName", "customerEmail")))
                .andExpect(jsonPath("$.errors[0].message").isNotEmpty());

        verifyNoInteractions(orders);
    }

    @Test
    void refusesOrderWithoutItems() throws Exception {
        postOrder("""
                {"customerId":"c-1001","customerName":"Ana Souza","customerEmail":"ana@exemplo.com","items":[]}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(hasItem("items")));
    }

    @Test
    void refusesMoreThanTwentyItems() throws Exception {
        String items = String.join(",", IntStream.rangeClosed(1, 21)
                .mapToObj(i -> "{\"productId\":\"%s\",\"quantity\":1}".formatted(UUID.randomUUID()))
                .toList());

        postOrder("""
                {"customerId":"c-1001","customerName":"Ana Souza","customerEmail":"ana@exemplo.com","items":[%s]}
                """.formatted(items))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(hasItem("items")));
    }

    @Test
    void refusesQuantityOutOfRange() throws Exception {
        postOrder("""
                {"customerId":"c-1001","customerName":"Ana Souza","customerEmail":"ana@exemplo.com",
                 "items":[{"productId":"%s","quantity":0},{"productId":"%s","quantity":101}]}
                """.formatted(KEYBOARD, MOUSE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field")
                        .value(containsInAnyOrder("items[0].quantity", "items[1].quantity")));
    }

    @Test
    void refusesTheSameProductTwice() throws Exception {
        postOrder("""
                {"customerId":"c-1001","customerName":"Ana Souza","customerEmail":"ana@exemplo.com",
                 "items":[{"productId":"%s","quantity":1},{"productId":"%s","quantity":3}]}
                """.formatted(KEYBOARD, KEYBOARD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value("items"))
                .andExpect(jsonPath("$.errors[0].message").value("must not list the same product twice"));
    }

    @Test
    void refusesProductIdThatIsNotAUuidNamingTheField() throws Exception {
        postOrder("""
                {"customerId":"c-1001","customerName":"Ana Souza","customerEmail":"ana@exemplo.com",
                 "items":[{"productId":"keyboard","quantity":1}]}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.errors[0].field").value("items[0].productId"));
    }

    @Test
    void refusesBodyThatIsNotJson() throws Exception {
        postOrder("{ not json")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void answersNotFoundForUnknownOrder() throws Exception {
        UUID id = UUID.randomUUID();
        when(orders.get(id)).thenThrow(new OrderNotFoundException(id));

        mvc.perform(get("/orders/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Order " + id + " not found"))
                .andExpect(jsonPath("$.instance").value("/orders/" + id));
    }

    @Test
    void answersBadRequestForIdThatIsNotAUuid() throws Exception {
        mvc.perform(get("/orders/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM));
    }

    @Test
    void listsWithFiltersAndDefaultPaging() throws Exception {
        Order order = Order.place("c-1001", "Ana Souza", "ana@exemplo.com",
                List.of(OrderItem.requested(UUID.fromString(KEYBOARD), 2), OrderItem.requested(UUID.fromString(MOUSE), 1)));
        when(orders.search(OrderStatus.PENDING, "c-1001", 0, 20))
                .thenReturn(new PageImpl<>(List.of(order), PageRequest.of(0, 20), 1));

        mvc.perform(get("/orders").param("status", "PENDING").param("customerId", "c-1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.content[0].customerName").value("Ana Souza"))
                .andExpect(jsonPath("$.content[0].status").value("PENDING"))
                .andExpect(jsonPath("$.content[0].itemCount").value(2))
                .andExpect(jsonPath("$.content[0].items").doesNotExist());
    }

    @Test
    void treatsEmptyFiltersAsNoFilter() throws Exception {
        when(orders.search(null, "", 0, 20)).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mvc.perform(get("/orders?status=&customerId=&page=&size="))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)));
    }

    @Test
    void refusesPageSizeOutOfRange() throws Exception {
        mvc.perform(get("/orders").param("size", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.errors[0].field").value("size"));
    }

    @Test
    void refusesUnknownStatusFilter() throws Exception {
        mvc.perform(get("/orders").param("status", "SHIPPED"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM));
    }

    @Test
    void answersProblemForMethodNotAllowed() throws Exception {
        mvc.perform(put("/orders"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Method Not Allowed"))
                .andExpect(jsonPath("$.instance").value("/orders"));
    }

    @Test
    void hidesTheCauseOfAnUnexpectedError() throws Exception {
        when(orders.get(any())).thenThrow(new IllegalStateException("connection refused to 10.0.0.7"));

        mvc.perform(get("/orders/{id}", UUID.randomUUID()))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.detail").value("Something went wrong on our side. Try again in a moment."));
    }

    private ResultActions postOrder(String body) throws Exception {
        return mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
