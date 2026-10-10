# order-platform

Três serviços em Java (pedidos, estoque e pagamento) que processam um pedido trocando eventos pelo Kafka, cada um com o seu banco PostgreSQL.

Ainda estou construindo. Por enquanto o repositório tem só a base: os três serviços sobem com `docker compose up -d --build --wait` e respondem em `/actuator/health`.
