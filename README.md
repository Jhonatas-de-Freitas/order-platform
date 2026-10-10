# order-platform

Três serviços em Java (pedidos, estoque e pagamento) que processam um pedido trocando eventos pelo Kafka, cada um com o seu banco PostgreSQL.

Ainda estou construindo. O que já existe:

- A base: os três serviços sobem com `docker compose up -d --build --wait` e respondem em `/actuator/health`.
- O `order-service` completo: recebe o pedido (`POST /orders` em `http://localhost:8081`), grava o evento no outbox na mesma transação, publica no Kafka e acompanha o pedido até `CONFIRMED` ou `CANCELLED` conforme os eventos de estoque e de pagamento chegam. A documentação da API fica em `http://localhost:8081/swagger-ui.html`.
- O `inventory-service` completo: catálogo com seis produtos de exemplo (`GET /products` em `http://localhost:8082`), reserva de estoque tudo ou nada a cada `OrderCreated`, com o preço do catálogo, e devolução do saldo quando o pedido é cancelado (`GET /reservations/{orderId}` mostra a reserva). Dois pedidos disputando o mesmo produto esperam um pelo outro numa trava de linha no banco, então o saldo nunca fica negativo. A documentação da API fica em `http://localhost:8082/swagger-ui.html`.

O `payment-service` ainda só sobe. Sem ele o pedido para em `STOCK_RESERVED`, ou vai para `CANCELLED` quando falta estoque.
