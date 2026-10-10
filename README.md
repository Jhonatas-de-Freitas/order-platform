# order-platform

Três serviços em Java (pedidos, estoque e pagamento) que processam um pedido trocando eventos pelo Kafka, cada um com o seu banco PostgreSQL.

Ainda estou construindo. O que já existe:

- A base: os três serviços sobem com `docker compose up -d --build --wait` e respondem em `/actuator/health`.
- O `order-service` completo: recebe o pedido (`POST /orders` em `http://localhost:8081`), grava o evento no outbox na mesma transação, publica no Kafka e acompanha o pedido até `CONFIRMED` ou `CANCELLED` conforme os eventos de estoque e de pagamento chegam. A documentação da API fica em `http://localhost:8081/swagger-ui.html`.
- O `inventory-service` completo: catálogo com seis produtos de exemplo (`GET /products` em `http://localhost:8082`), reserva de estoque tudo ou nada a cada `OrderCreated`, com o preço do catálogo, e devolução do saldo quando o pedido é cancelado (`GET /reservations/{orderId}` mostra a reserva). Dois pedidos disputando o mesmo produto esperam um pelo outro numa trava de linha no banco, então o saldo nunca fica negativo. A documentação da API fica em `http://localhost:8082/swagger-ui.html`.

- O `payment-service` completo: a cada `StockReserved` cobra o total do pedido (simulado) e publica `PaymentApproved` ou `PaymentRefused`. Acima de 5000.00 o pagamento é recusado com `LIMIT_EXCEEDED`; o limite muda pela variável `PAYMENT_APPROVAL_LIMIT`. Cada pedido tem no máximo um pagamento, mesmo que o evento chegue repetido. `GET /payments/{orderId}` em `http://localhost:8083` mostra o pagamento, e a documentação da API fica em `http://localhost:8083/swagger-ui.html`.

Para ver os cenários rodando contra o sistema de verdade, com o compose no ar:

```bash
./scripts/smoke-test.sh
```

Ele precisa de `curl` e `jq` e confere o caminho feliz, a falta de estoque, o pagamento recusado (com o saldo voltando), o pedido tudo ou nada e o `payment-service` fora do ar (o script para o container, vê o pedido esperando em `STOCK_RESERVED` e sobe de novo até o pedido chegar a `CONFIRMED`). Para no primeiro cenário que falhar, dizendo o que esperava e o que recebeu. É o mesmo script que roda no CI.
