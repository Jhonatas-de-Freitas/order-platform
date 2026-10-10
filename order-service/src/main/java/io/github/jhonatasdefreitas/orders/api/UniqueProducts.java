package io.github.jhonatasdefreitas.orders.api;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = UniqueProducts.Validator.class)
public @interface UniqueProducts {

    String message() default "must not list the same product twice";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<UniqueProducts, List<CreateOrderRequest.Item>> {

        @Override
        public boolean isValid(List<CreateOrderRequest.Item> items, ConstraintValidatorContext context) {
            if (items == null) {
                return true;
            }
            Set<UUID> seen = new HashSet<>();
            return items.stream()
                    .filter(Objects::nonNull)
                    .map(CreateOrderRequest.Item::productId)
                    .filter(Objects::nonNull)
                    .allMatch(seen::add);
        }
    }
}
