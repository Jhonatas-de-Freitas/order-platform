package io.github.jhonatasdefreitas.inventory.api;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.jhonatasdefreitas.inventory.domain.Product;
import io.github.jhonatasdefreitas.inventory.domain.RequestedItem;
import io.github.jhonatasdefreitas.inventory.domain.Reservation;
import io.github.jhonatasdefreitas.inventory.domain.ReservationNotFoundException;
import io.github.jhonatasdefreitas.inventory.domain.ReservationService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ReservationController.class)
class ReservationControllerTest {

    private static final UUID KEYBOARD = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ReservationService reservations;

    @Test
    void showsWhatAnOrderHasReserved() throws Exception {
        UUID orderId = UUID.randomUUID();
        Product keyboard = new Product("Teclado Mecânico", null, new BigDecimal("349.90"), 50);
        ReflectionTestUtils.setField(keyboard, "id", KEYBOARD);
        Reservation reservation = Reservation.of(orderId, List.of(new RequestedItem(KEYBOARD, 2)),
                Map.of(KEYBOARD, keyboard));
        when(reservations.get(orderId)).thenReturn(reservation);

        mvc.perform(get("/reservations/{orderId}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.status").value("RESERVED"))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].productId").value(KEYBOARD.toString()))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.items[0].unitPrice").value(349.90))
                .andExpect(jsonPath("$.createdAt").value(reservation.getCreatedAt().toString()))
                .andExpect(jsonPath("$.releasedAt").value((Object) null));
    }

    @Test
    void answersNotFoundForAnOrderWithoutReservation() throws Exception {
        UUID orderId = UUID.randomUUID();
        when(reservations.get(orderId)).thenThrow(new ReservationNotFoundException(orderId));

        mvc.perform(get("/reservations/{orderId}", orderId))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Reservation for order " + orderId + " not found"))
                .andExpect(jsonPath("$.instance").value("/reservations/" + orderId));
    }

    @Test
    void answersBadRequestForOrderIdThatIsNotAUuid() throws Exception {
        mvc.perform(get("/reservations/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }
}
