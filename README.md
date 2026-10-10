# order-platform

Três serviços em Java (pedidos, estoque e pagamento) que processam um pedido trocando eventos pelo Kafka, cada um com o seu banco PostgreSQL.

Ainda estou construindo. O que já existe:

- A base: os três serviços sobem com `docker compose up -d --build --wait` e respondem em `/actuator/health`.
- O `order-service` completo: recebe o pedido (`POST /orders` em `http://localhost:8081`), grava o evento no outbox na mesma transação, publica no Kafka e acompanha o pedido até `CONFIRMED` ou `CANCELLED` conforme os eventos de estoque e de pagamento chegam. A documentação da API fica em `http://localhost:8081/swagger-ui.html`.

O `inventory-service` e o `payment-service` ainda só sobem. Sem eles o pedido fica em `PENDING`.
