package io.github.jhonatasdefreitas.payments.messaging;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ConsumerRetry.class)
class ConsumerErrorHandling {

    private static final Logger log = LoggerFactory.getLogger(ConsumerErrorHandling.class);

    // Boot plugs the only CommonErrorHandler bean into the listener container.
    @Bean
    DefaultErrorHandler errorHandler(KafkaTemplate<String, String> kafka, ConsumerRetry retry, MeterRegistry meters) {
        // Negative partition: the producer picks it from the key, so the order keeps its partition there too.
        DeadLetterPublishingRecoverer deadLetters = new DeadLetterPublishingRecoverer(kafka,
                (record, exception) -> new TopicPartition(Topics.DEAD_LETTER, -1));
        Counter deadLetterCount = Counter.builder("dead.letter.messages")
                .description("Messages given up on and sent to the dead letter topic")
                .register(meters);
        ConsumerRecordRecoverer recoverer = (record, exception) -> {
            deadLetters.accept(record, exception);
            deadLetterCount.increment();
            // The container wraps what the listener threw. The cause is the part worth reading.
            Throwable failure = exception.getCause() != null ? exception.getCause() : exception;
            log.error("Message {}-{}@{} with key {} sent to {}: {}", record.topic(), record.partition(),
                    record.offset(), record.key(), Topics.DEAD_LETTER, failure.getMessage());
        };

        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(retry.maxAttempts() - 1);
        backOff.setInitialInterval(retry.initialInterval().toMillis());
        backOff.setMultiplier(retry.multiplier());
        backOff.setMaxInterval(retry.maxInterval().toMillis());

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);
        // Trying again cannot fix a message that is unreadable.
        errorHandler.addNotRetryableExceptions(InvalidEventException.class);
        return errorHandler;
    }
}
