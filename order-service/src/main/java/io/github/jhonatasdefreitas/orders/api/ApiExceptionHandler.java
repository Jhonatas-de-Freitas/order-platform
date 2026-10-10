package io.github.jhonatasdefreitas.orders.api;

import io.github.jhonatasdefreitas.orders.domain.OrderNotFoundException;
import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DatabindException;

/**
 * Every error leaves as application/problem+json. The base class already does that for the errors
 * raised by Spring MVC. This class adds the ones of this API and the "errors" list for invalid input.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    // Spring leaves "type" out when it is not set. The contract lists it in every problem, so the
    // default of RFC 9457 is written explicitly: the title is just the name of the HTTP status.
    private static final URI NO_SPECIFIC_TYPE = URI.create("about:blank");

    record FieldMessage(String field, String message) {
    }

    @ExceptionHandler(OrderNotFoundException.class)
    ProblemDetail orderNotFound(OrderNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception exception) {
        log.error("Unexpected error", exception);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong on our side. Try again in a moment.");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldMessage> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldMessage(error.getField(), error.getDefaultMessage()))
                .toList();
        return invalid(exception, errors, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException exception, HttpHeaders headers, HttpStatusCode status,
            WebRequest request) {
        List<FieldMessage> errors = exception.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new FieldMessage(result.getMethodParameter().getParameterName(),
                                error.getDefaultMessage())))
                .toList();
        return invalid(exception, errors, headers, status, request);
    }

    // A value of the wrong type (a quantity that is not a number, a product id that is not a UUID) fails
    // while the body is read, before validation. Jackson knows where, so the field is reported all the same.
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (exception.getCause() instanceof DatabindException cause && !cause.getPath().isEmpty()) {
            List<FieldMessage> errors = List.of(new FieldMessage(fieldPath(cause), "has an invalid value"));
            return invalid(exception, errors, headers, status, request);
        }
        return super.handleHttpMessageNotReadable(exception, headers, status, request);
    }

    private ResponseEntity<Object> invalid(Exception exception, List<FieldMessage> errors, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = problem(status, "The request has invalid fields");
        problem.setProperty("errors", errors);
        return handleExceptionInternal(exception, problem, headers, status, request);
    }

    // The errors answered by the base class pass through here.
    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers, HttpStatusCode status,
            WebRequest request) {
        if (body instanceof ProblemDetail problem && problem.getType() == null) {
            problem.setType(NO_SPECIFIC_TYPE);
        }
        return super.createResponseEntity(body, headers, status, request);
    }

    private static ProblemDetail problem(HttpStatusCode status, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(NO_SPECIFIC_TYPE);
        return problem;
    }

    private static String fieldPath(DatabindException exception) {
        return exception.getPath().stream()
                .map(ApiExceptionHandler::pathElement)
                .collect(Collectors.joining())
                .replaceFirst("^\\.", "");
    }

    private static String pathElement(JacksonException.Reference reference) {
        return reference.getPropertyName() != null
                ? "." + reference.getPropertyName()
                : "[" + reference.getIndex() + "]";
    }
}
