#!/usr/bin/env bash
# Shows the dead letter topic at work, with the stack started by "docker compose up -d --build --wait".
# Publishes a message that is not JSON to orders.events, shows that the inventory-service sent it to
# inventory-service.dlt with the reason in the headers, and that the next order still goes through.
# Needs curl and jq.
set -euo pipefail

cd "$(dirname "$0")/.."

ORDER_URL="${ORDER_URL:-http://localhost:8081}"
INVENTORY_URL="${INVENTORY_URL:-http://localhost:8082}"
COMPOSE="${COMPOSE:-docker compose}"
WAIT_SECONDS="${WAIT_SECONDS:-60}"

TOPIC=orders.events
DEAD_LETTERS=inventory-service.dlt
key="demo-$(date +%s)"
message="isto nao e um evento"

kafka() {
  local tool=$1
  shift
  $COMPOSE exec -T kafka "/opt/kafka/bin/$tool" --bootstrap-server kafka:9092 "$@"
}

# One "partition:offset" per line: where the next message of each partition will go.
end_offsets() {
  kafka kafka-get-offsets.sh --topic "$DEAD_LETTERS" | cut -d: -f2,3 | sort
}

fail() {
  echo >&2
  echo "FAILED: $*" >&2
  exit 1
}

before=$(end_offsets)

echo "== Publishing to $TOPIC, key $key: \"$message\""
echo "$key|$message" | kafka kafka-console-producer.sh --topic "$TOPIC" \
  --reader-property parse.key=true --reader-property 'key.separator=|'

echo
echo "== Waiting for it in $DEAD_LETTERS"
deadline=$((SECONDS + WAIT_SECONDS))
while true; do
  after=$(end_offsets)
  # The partition whose end moved, and the offset it had before: that is where the message is.
  landed=$(comm -23 <(echo "$before") <(echo "$after") | head -1)
  [ -n "$landed" ] && break
  [ "$SECONDS" -lt "$deadline" ] || fail "nothing arrived in $DEAD_LETTERS after ${WAIT_SECONDS}s"
  sleep 1
done
partition=${landed%%:*}
offset=${landed##*:}

read_dead_letter() {
  kafka kafka-console-consumer.sh --topic "$DEAD_LETTERS" --partition "$partition" --offset "$offset" \
    --max-messages 1 --timeout-ms 10000 "$@" 2> /dev/null
}

echo "  partition $partition, offset $offset"
echo
echo "  key and value, as they were published:"
read_dead_letter --formatter-property print.key=true --formatter-property 'key.separator= | ' | sed 's/^/    /'
echo
echo "  why it is there (headers added by the service):"
read_dead_letter --formatter-property print.headers=true --formatter-property print.value=false \
  --formatter-property "headers.separator=
" | grep -aE '^kafka_dlt-(original-topic|exception-cause-fqcn|exception-message):' \
  | sed -e "s/Listener method .* threw exception; //" -e 's/^/    /'

echo
echo "== Counter of the inventory-service"
curl -fsS "$INVENTORY_URL/actuator/prometheus" | grep '^dead_letter_messages_total' | sed 's/^/  /'

echo
echo "== The next order still goes through"
order=$(curl -fsS -X POST "$ORDER_URL/orders" -H 'Content-Type: application/json' \
  -d '{"customerId":"c-1001","customerName":"Ana Souza","customerEmail":"ana@exemplo.com",
       "items":[{"productId":"22222222-2222-2222-2222-222222222222","quantity":1}]}' | jq -er '.id')
deadline=$((SECONDS + WAIT_SECONDS))
until [ "$(curl -fsS "$ORDER_URL/orders/$order" | jq -r '.status')" = "CONFIRMED" ]; do
  [ "$SECONDS" -lt "$deadline" ] || fail "order $order is not CONFIRMED after ${WAIT_SECONDS}s"
  sleep 1
done
echo "  order $order is CONFIRMED"
