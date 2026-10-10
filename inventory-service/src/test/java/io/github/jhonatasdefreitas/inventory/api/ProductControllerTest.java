package io.github.jhonatasdefreitas.inventory.api;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.jhonatasdefreitas.inventory.domain.Product;
import io.github.jhonatasdefreitas.inventory.domain.ProductNotFoundException;
import io.github.jhonatasdefreitas.inventory.domain.ProductService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@WebMvcTest(ProductController.class)
class ProductControllerTest {

    private static final UUID KEYBOARD = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MOUSE = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final MediaType PROBLEM = MediaType.APPLICATION_PROBLEM_JSON;

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ProductService products;

    @Test
    void listsTheCatalogAsAPlainArray() throws Exception {
        when(products.list()).thenReturn(List.of(
                product(MOUSE, "Mouse Sem Fio", "Mouse óptico", "129.90", 80),
                product(KEYBOARD, "Teclado Mecânico", "Teclado ABNT2", "349.90", 50)));

        mvc.perform(get("/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(MOUSE.toString()))
                .andExpect(jsonPath("$[1].id").value(KEYBOARD.toString()))
                .andExpect(jsonPath("$[1].name").value("Teclado Mecânico"))
                .andExpect(jsonPath("$[1].description").value("Teclado ABNT2"))
                .andExpect(jsonPath("$[1].price").value(349.90))
                .andExpect(jsonPath("$[1].availableQuantity").value(50));
    }

    @Test
    void writesThePriceWithTwoDecimalPlaces() throws Exception {
        when(products.get(KEYBOARD)).thenReturn(product(KEYBOARD, "Teclado Mecânico", null, "349.9", 50));

        mvc.perform(get("/products/{id}", KEYBOARD))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"id":"11111111-1111-1111-1111-111111111111","name":"Teclado Mecânico","description":null,
                         "price":349.90,"availableQuantity":50}
                        """))
                .andExpect(content().string(containsString("\"price\":349.90")));
    }

    @Test
    void answersNotFoundForUnknownProduct() throws Exception {
        UUID id = UUID.randomUUID();
        when(products.get(id)).thenThrow(new ProductNotFoundException(id));

        mvc.perform(get("/products/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Product " + id + " not found"))
                .andExpect(jsonPath("$.instance").value("/products/" + id));
    }

    @Test
    void answersBadRequestForIdThatIsNotAUuid() throws Exception {
        mvc.perform(get("/products/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM));
    }

    @Test
    void createsAProductAndPointsToIt() throws Exception {
        when(products.create("Mousepad", "Mousepad de tecido", new BigDecimal("59.90"), 25))
                .thenReturn(product(MOUSE, "Mousepad", "Mousepad de tecido", "59.90", 25));

        postProduct("""
                {"name":"Mousepad","description":"Mousepad de tecido","price":59.90,"availableQuantity":25}
                """)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/products/" + MOUSE))
                .andExpect(jsonPath("$.id").value(MOUSE.toString()))
                .andExpect(jsonPath("$.name").value("Mousepad"))
                .andExpect(jsonPath("$.price").value(59.90))
                .andExpect(jsonPath("$.availableQuantity").value(25));
    }

    @Test
    void descriptionIsOptional() throws Exception {
        when(products.create("Mousepad", null, new BigDecimal("59.90"), 0))
                .thenReturn(product(MOUSE, "Mousepad", null, "59.90", 0));

        postProduct("""
                {"name":"Mousepad","price":59.90,"availableQuantity":0}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.description").value((Object) null));
    }

    @Test
    void refusesProductWithoutNamePriceOrStockNamingEachField() throws Exception {
        postProduct("""
                {"name":" ","description":"Mousepad de tecido"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").exists())
                .andExpect(jsonPath("$.instance").value("/products"))
                .andExpect(jsonPath("$.errors[*].field")
                        .value(containsInAnyOrder("name", "price", "availableQuantity")))
                .andExpect(jsonPath("$.errors[0].message").isNotEmpty());

        verifyNoInteractions(products);
    }

    @Test
    void refusesPriceThatIsZeroOrHasMoreThanTwoDecimalPlaces() throws Exception {
        postProduct("""
                {"name":"Mousepad","price":0,"availableQuantity":1}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("price"));

        postProduct("""
                {"name":"Mousepad","price":59.999,"availableQuantity":1}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("price"));
    }

    @Test
    void refusesPriceThatIsNotANumberNamingTheField() throws Exception {
        postProduct("""
                {"name":"Mousepad","price":"cheap","availableQuantity":1}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.errors[0].field").value("price"));
    }

    @Test
    void refusesBodyThatIsNotJson() throws Exception {
        postProduct("{ not json")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void setsTheStock() throws Exception {
        when(products.setStock(KEYBOARD, 50)).thenReturn(product(KEYBOARD, "Teclado Mecânico", null, "349.90", 50));

        putStock(KEYBOARD, """
                {"availableQuantity":50}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(KEYBOARD.toString()))
                .andExpect(jsonPath("$.availableQuantity").value(50));
    }

    @Test
    void refusesNegativeOrMissingStock() throws Exception {
        putStock(KEYBOARD, """
                {"availableQuantity":-1}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.errors[0].field").value("availableQuantity"));

        putStock(KEYBOARD, "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("availableQuantity"));

        verifyNoInteractions(products);
    }

    @Test
    void answersNotFoundWhenSettingTheStockOfUnknownProduct() throws Exception {
        UUID id = UUID.randomUUID();
        when(products.setStock(id, 5)).thenThrow(new ProductNotFoundException(id));

        putStock(id, """
                {"availableQuantity":5}
                """)
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.detail").value("Product " + id + " not found"));
    }

    @Test
    void answersProblemForMethodNotAllowed() throws Exception {
        mvc.perform(delete("/products/{id}", KEYBOARD))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Method Not Allowed"))
                .andExpect(jsonPath("$.instance").value("/products/" + KEYBOARD));
    }

    @Test
    void hidesTheCauseOfAnUnexpectedError() throws Exception {
        when(products.setStock(any(), anyInt())).thenThrow(new IllegalStateException("connection refused to 10.0.0.7"));

        putStock(KEYBOARD, """
                {"availableQuantity":5}
                """)
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.detail").value("Something went wrong on our side. Try again in a moment."));
    }

    private ResultActions postProduct(String body) throws Exception {
        return mvc.perform(post("/products").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions putStock(UUID id, String body) throws Exception {
        return mvc.perform(put("/products/{id}/stock", id).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static Product product(UUID id, String name, String description, String price, int availableQuantity) {
        Product product = new Product(name, description, new BigDecimal(price), availableQuantity);
        ReflectionTestUtils.setField(product, "id", id);
        return product;
    }
}
