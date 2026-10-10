#!/usr/bin/env bash
# Checks the metrics side of the stack after the smoke test: Prometheus scrapes the three services,
# the business metrics have values, and every query of the Grafana dashboard brings data through Grafana.
# Needs curl and jq.
set -euo pipefail

cd "$(dirname "$0")/.."

PROMETHEUS_URL="${PROMETHEUS_URL:-http://localhost:9090}"
GRAFANA_URL="${GRAFANA_URL:-http://localhost:3000}"
# Prometheus scrapes every 5 s, so what the smoke test did takes a moment to show up.
WAIT_SECONDS="${WAIT_SECONDS:-60}"
DASHBOARD=docker/grafana/provisioning/dashboards/order-platform.json

fail() {
  echo >&2
  echo "FAILED: $*" >&2
  exit 1
}

# Each series the query answers, as "label=value ... sample".
prometheus() {
  curl -fsS -G "$PROMETHEUS_URL/api/v1/query" --data-urlencode "query=$1" \
    | jq -c '[.data.result[] | "\(.metric | del(.__name__) | to_entries | map("\(.key)=\(.value)") | join(" ")) \(.value[1])"]'
}

# The same, asked to Grafana the way a panel asks it.
grafana() {
  jq -n --arg expr "$1" \
      '{queries: [{refId: "A", datasource: {uid: "prometheus"}, expr: $expr, instant: true}], from: "now-5m", to: "now"}' \
    | curl -fsS -X POST "$GRAFANA_URL/api/ds/query" -H 'Content-Type: application/json' -d @- \
    | jq -c '[.results.A.frames[]? | select((.data.values[1] // []) | length > 0)
              | "\(.schema.fields[1].labels // {} | to_entries | map("\(.key)=\(.value)") | join(" ")) \(.data.values[1][0])"]'
}

# Polls until the query answers at least one series, then prints what it answered.
expect_data() {
  local source=$1 expr=$2 result
  local deadline=$((SECONDS + WAIT_SECONDS))
  while true; do
    result=$("$source" "$expr" 2> /dev/null || echo '[]')
    if [ "$result" != "[]" ]; then
      echo "  ok: $expr"
      echo "      $result"
      return
    fi
    if [ "$SECONDS" -ge "$deadline" ]; then
      fail "$expr: no data from $source after ${WAIT_SECONDS}s"
    fi
    sleep 2
  done
}

services="order-service inventory-service payment-service"

echo "== Prometheus scrapes the three services"
for job in $services; do
  expect_data prometheus "up{job=\"$job\"} == 1"
done

echo
echo "== The business metrics have values"
expect_data prometheus 'orders_status_changes_total{status="CONFIRMED"} > 0'
expect_data prometheus 'orders_status_changes_total{status="CANCELLED"} > 0'
expect_data prometheus 'orders_time_to_final_seconds_count{status="CONFIRMED"} > 0'
expect_data prometheus 'orders_time_to_final_seconds_count{status="CANCELLED"} > 0'
expect_data prometheus 'stock_reservations_total{outcome="reserved"} > 0'
expect_data prometheus 'stock_reservations_total{outcome="released"} > 0'
# The smoke test restarts the payment-service, which starts its counters again. The last order is approved.
expect_data prometheus 'payments_total{outcome="approved"} > 0'
for job in $services; do
  expect_data prometheus "outbox_pending_events{job=\"$job\"}"
  expect_data prometheus "dead_letter_messages_total{job=\"$job\"}"
done

echo
echo "== The Grafana dashboard is there and each of its queries has data"
title=$(curl -fsS "$GRAFANA_URL/api/dashboards/uid/order-platform" | jq -er '.dashboard.title') \
  || fail "the dashboard order-platform is not in Grafana"
echo "  ok: dashboard \"$title\""
while IFS= read -r expr; do
  expect_data grafana "$expr"
done < <(jq -r '.panels[].targets[].expr' "$DASHBOARD")

echo
echo "Metrics and dashboard are fine."
