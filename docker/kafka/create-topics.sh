#!/bin/bash
set -euo pipefail

topics="orders.events inventory.events payments.events order-service.dlt inventory-service.dlt payment-service.dlt"

for topic in $topics; do
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 \
    --create --if-not-exists --topic "$topic" --partitions 3 --replication-factor 1
done
