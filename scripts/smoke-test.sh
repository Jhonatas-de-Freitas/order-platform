#!/usr/bin/env bash
# Runs the order scenarios against the stack started with "docker compose up -d --build --wait".
# Needs curl and jq. The last scenario stops and starts the payment-service container.
# Stops at the first scenario that fails, saying what was expected and what the service answered.
set -euo pipefail

cd "$(dirname "$0")/.."

ORDER_URL="${ORDER_URL:-http://localhost:8081}"
INVENTORY_URL="${INVENTORY_URL:-http://localhost:8082}"
PAYMENT_URL="${PAYMENT_URL:-http://localhost:8083}"
COMPOSE="${COMPOSE:-docker compose}"
# How long to wait for an order to get where the scenario expects.
WAIT_SECONDS="${WAIT_SECONDS:-60}"

KEYBOARD=11111111-1111-1111-1111-111111111111
MOUSE=22222222-2222-2222-2222-222222222222
HEADSET=44444444-4444-4444-4444-444444444444
NOTEBOOK=55555555-5555-5555-5555-555555555555

current="setup"
body=""

scenario() {
  current=$1
  echo
  echo "== $current"
}

fail() {
  echo >&2
  echo "FAILED: $current" >&2
  echo "  $*" >&2
  exit 1
}

ok() {
  echo "  ok: $*"
}

get() {
  curl -fsS "$1" || fail "GET $1 did not answer 200"
}

item() {
  echo '{"productId":"'"$1"'","quantity":'"$2"'}'
}

# Places an order for the items given and prints its id.
place_order() {
  curl -fsS -X POST "$ORDER_URL/orders" -H 'Content-Type: application/json' \
    -d '{"customerId":"c-1001","customerName":"Ana Souza","customerEmail":"ana@exemplo.com","items":['"$1"']}' \
    | jq -er '.id' || fail "POST $ORDER_URL/orders was not accepted"
}

stock_of() {
  get "$INVENTORY_URL/products/$1" | jq -er '.availableQuantity'
}

# Polls the URL until the jq filter is true for what it answers. The last answer stays in $body.
wait_for() {
  local what=$1 url=$2 filter=$3 seconds=${4:-$WAIT_SECONDS}
  local deadline=$((SECONDS + seconds))
  while true; do
    body=$(curl -sS "$url" 2> /dev/null || true)
    if jq -e "$filter" <<< "$body" > /dev/null 2>&1; then
      ok "$what"
      return
    fi
    if [ "$SECONDS" -ge "$deadline" ]; then
      fail "$what: not true after ${seconds}s. Expected $filter, last answer of $url: $body"
    fi
    sleep 1
  done
}

# Checks the jq filter against the JSON in $body.
expect() {
  local what=$1 filter=$2
  jq -e "$filter" <<< "$body" > /dev/null || fail "$what: expected $filter, got $body"
  ok "$what"
}

expect_http() {
  local what=$1 url=$2 expected=$3 actual
  actual=$(curl -sS -o /dev/null -w '%{http_code}' "$url") || fail "$what: $url did not answer"
  [ "$actual" = "$expected" ] || fail "$what: $url answered $actual, expected $expected"
  ok "$what"
}

expect_stock() {
  local what=$1 product=$2 expected=$3 actual
  actual=$(stock_of "$product")
  [ "$actual" = "$expected" ] || fail "$what: stock is $actual, expected $expected"
  ok "$what ($actual)"
}

scenario "Happy path: the order is confirmed, paid and taken from the stock"
keyboards=$(stock_of $KEYBOARD)
order=$(place_order "$(item $KEYBOARD 2)")
wait_for "order $order is CONFIRMED" "$ORDER_URL/orders/$order" '.status == "CONFIRMED"'
expect "total is 699.80" '.totalAmount == 699.80'
expect "item got the name and the price of the catalog" \
  '.items[0].productName == "Teclado Mecânico" and .items[0].unitPrice == 349.90'
expect "history shows every step" '[.history[].status] == ["PENDING", "STOCK_RESERVED", "CONFIRMED"]'
body=$(get "$PAYMENT_URL/payments/$order")
expect "payment is APPROVED for 699.80" '.status == "APPROVED" and .amount == 699.80 and .reason == null'
body=$(get "$INVENTORY_URL/reservations/$order")
expect "reservation is RESERVED" '.status == "RESERVED" and .items[0].quantity == 2'
expect_stock "keyboard stock went from $keyboards to $((keyboards - 2))" $KEYBOARD $((keyboards - 2))

scenario "Out of stock: the order is cancelled and nothing is charged"
order=$(place_order "$(item $HEADSET 1)")
wait_for "order $order is CANCELLED" "$ORDER_URL/orders/$order" '.status == "CANCELLED"'
expect "reason is OUT_OF_STOCK" '.cancellationReason == "OUT_OF_STOCK" and .cancellationDetail != null'
expect_http "no reservation was made" "$INVENTORY_URL/reservations/$order" 404
expect_http "no payment was created" "$PAYMENT_URL/payments/$order" 404

scenario "Payment refused: the order is cancelled and the stock goes back"
notebooks=$(stock_of $NOTEBOOK)
order=$(place_order "$(item $NOTEBOOK 1)")
wait_for "order $order is CANCELLED" "$ORDER_URL/orders/$order" '.status == "CANCELLED"'
expect "reason is PAYMENT_REFUSED" \
  '.cancellationReason == "PAYMENT_REFUSED" and .cancellationDetail == "Pagamento recusado: LIMIT_EXCEEDED"'
body=$(get "$PAYMENT_URL/payments/$order")
expect "payment is REFUSED for 7499.00" '.status == "REFUSED" and .amount == 7499.00 and .reason == "LIMIT_EXCEEDED"'
wait_for "reservation is RELEASED" "$INVENTORY_URL/reservations/$order" '.status == "RELEASED" and .releasedAt != null'
expect_stock "notebook stock is back to $notebooks" $NOTEBOOK "$notebooks"

scenario "All or nothing: one item without stock and nothing is reserved"
mice=$(stock_of $MOUSE)
order=$(place_order "$(item $MOUSE 1),$(item $HEADSET 1)")
wait_for "order $order is CANCELLED" "$ORDER_URL/orders/$order" '.status == "CANCELLED"'
expect "reason is OUT_OF_STOCK" '.cancellationReason == "OUT_OF_STOCK"'
expect_http "no reservation was made" "$INVENTORY_URL/reservations/$order" 404
expect_http "no payment was created" "$PAYMENT_URL/payments/$order" 404
expect_stock "mouse stock is still $mice" $MOUSE "$mice"

scenario "Last unit: two orders at the same time, one is confirmed and the other cancelled"
cable=$(curl -fsS -X POST "$INVENTORY_URL/products" -H 'Content-Type: application/json' \
  -d '{"name":"Cabo HDMI 2m","description":"Ultima unidade","price":39.90,"availableQuantity":1}' \
  | jq -er '.id') || fail "POST $INVENTORY_URL/products was not accepted"
ok "product $cable created with 1 in stock"
# Both POSTs leave together; each prints the id of its order.
orders=($(place_order "$(item "$cable" 1)" & place_order "$(item "$cable" 1)" & wait))
[ "${#orders[@]}" -eq 2 ] || fail "expected two orders, got: ${orders[*]}"
for order in "${orders[@]}"; do
  wait_for "order $order is finished" "$ORDER_URL/orders/$order" '.status == "CONFIRMED" or .status == "CANCELLED"'
done
body=$(for order in "${orders[@]}"; do get "$ORDER_URL/orders/$order"; done \
  | jq -sc 'map({status, cancellationReason}) | sort_by(.status)')
expect "one is CANCELLED with OUT_OF_STOCK and the other CONFIRMED" \
  '. == [{"status": "CANCELLED", "cancellationReason": "OUT_OF_STOCK"}, {"status": "CONFIRMED", "cancellationReason": null}]'
expect_stock "stock of the cable is 0" "$cable" 0

scenario "Payment service down: the order waits and is confirmed when the service is back"
# Whatever happens next, the stack is not left without the payment-service.
trap '$COMPOSE start payment-service > /dev/null 2>&1 || true' EXIT
$COMPOSE stop payment-service
order=$(place_order "$(item $MOUSE 1)")
wait_for "order $order is STOCK_RESERVED" "$ORDER_URL/orders/$order" '.status == "STOCK_RESERVED"'
sleep 5
body=$(get "$ORDER_URL/orders/$order")
expect "order is still STOCK_RESERVED five seconds later" '.status == "STOCK_RESERVED"'
$COMPOSE start payment-service
trap - EXIT
wait_for "order $order is CONFIRMED" "$ORDER_URL/orders/$order" '.status == "CONFIRMED"' 120
body=$(get "$PAYMENT_URL/payments/$order")
expect "payment is APPROVED for 129.90" '.status == "APPROVED" and .amount == 129.90'

echo
echo "All scenarios passed."
