package io.github.jhonatasdefreitas.inventory.api;

import io.github.jhonatasdefreitas.inventory.domain.Product;
import io.github.jhonatasdefreitas.inventory.domain.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/products")
@Tag(name = "Products")
class ProductController {

    private final ProductService products;

    ProductController(ProductService products) {
        this.products = products;
    }

    @GetMapping
    @Operation(summary = "List the catalog, ordered by name")
    List<ProductResponse> list() {
        return products.list().stream().map(ProductResponse::from).toList();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a product with its price and available stock")
    ProductResponse get(@PathVariable UUID id) {
        return ProductResponse.from(products.get(id));
    }

    @PostMapping
    @Operation(summary = "Add a product to the catalog")
    ResponseEntity<ProductResponse> create(@Valid @RequestBody CreateProductRequest request) {
        Product product = products.create(request.name(), request.description(), request.price(),
                request.availableQuantity());
        return ResponseEntity.created(URI.create("/products/" + product.getId()))
                .body(ProductResponse.from(product));
    }

    @PutMapping("/{id}/stock")
    @Operation(summary = "Set the available stock of a product",
            description = "Replaces the balance. Stock already reserved for orders is not part of it.")
    ProductResponse setStock(@PathVariable UUID id, @Valid @RequestBody SetStockRequest request) {
        return ProductResponse.from(products.setStock(id, request.availableQuantity()));
    }
}
