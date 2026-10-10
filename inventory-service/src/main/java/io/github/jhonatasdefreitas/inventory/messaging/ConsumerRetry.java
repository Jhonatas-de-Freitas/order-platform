package io.github.jhonatasdefreitas.inventory.messaging;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.consumer.retry")
record ConsumerRetry(int maxAttempts, Duration initialInterval, double multiplier, Duration maxInterval) {
}
