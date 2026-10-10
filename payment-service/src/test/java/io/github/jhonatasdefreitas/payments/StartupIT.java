package io.github.jhonatasdefreitas.payments;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaAdmin;

class StartupIT extends IntegrationTestBase {

    @Autowired
    KafkaAdmin kafkaAdmin;

    @Test
    void healthIsUp() {
        client.get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP");
    }

    @Test
    void exposesPrometheusMetrics() {
        client.get().uri("/actuator/prometheus")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).value(body -> assertThat(body)
                        .contains("jvm_memory_used_bytes")
                        .contains("outbox_pending_events")
                        .contains("dead_letter_messages_total"));
    }

    @Test
    void connectsToTheBroker() {
        assertThat(kafkaAdmin.clusterId()).isNotBlank();
    }

    @Test
    void publishesTheApiDescription() {
        client.get().uri("/v3/api-docs")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.info.title").isEqualTo("payment-service")
                .jsonPath("$.paths['/payments'].get").exists()
                .jsonPath("$.paths['/payments/{orderId}'].get").exists();

        client.get().uri("/swagger-ui/index.html")
                .exchange()
                .expectStatus().isOk();
    }
}
