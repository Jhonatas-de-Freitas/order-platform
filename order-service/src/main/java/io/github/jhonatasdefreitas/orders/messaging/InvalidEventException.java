package io.github.jhonatasdefreitas.orders.messaging;

/** A message that will never be readable, no matter how many times it is retried. */
public class InvalidEventException extends RuntimeException {

    public InvalidEventException(String message) {
        super(message);
    }

    public InvalidEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
