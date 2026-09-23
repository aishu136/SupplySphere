#!/usr/bin/env bash
# End-to-end check of the running microservices stack (docker compose), through the API gateway.
# Verifies: event-carried state transfer, the delivery saga across three services, Flink alerting,
# gateway circuit-breaker fallbacks, dashboard degradation, and distributed tracing in Jaeger.
set -euo pipefail
if [ -n "${GITHUB_ACTIONS:-}" ]; then
  trap 'echo "::error title=e2e failed at line $LINENO::$BASH_COMMAND"' ERR
fi

GW="${GATEWAY_URL:-http://localhost:8080}"
FLINK="${FLINK_URL:-http://localhost:8082}"
JAEGER="${JAEGER_URL:-http://localhost:16686}"

step() { printf '\n== %s\n' "$*"; }
ok() { printf '   ok: %s\n' "$*"; }

# retry <seconds> <command...>: re-run until it succeeds or the time runs out.
retry() {
  local deadline=$((SECONDS + $1)); shift
  until "$@" >/dev/null 2>&1; do
    if (( SECONDS >= deadline )); then
      echo "   FAILED waiting for: $*"
      # Surfaces in the GitHub checks UI/API; harmless when run locally.
      [ -n "${GITHUB_ACTIONS:-}" ] && echo "::error title=e2e check failed::$*"
      return 1
    fi
    sleep 2
  done
}
json() { curl -fsS -H 'Content-Type: application/json' "$@"; }
qty() { curl -fsS "$GW/api/inventory/$1" | jq --arg wh "$2" '.[] | select(.warehouseCode == $wh) | .quantity'; }

step "Gateway and all services are up"
retry 180 curl -fsS "$GW/api/dashboard"
retry 120 bash -c "curl -fsS '$GW/api/dashboard' | jq -e '.unavailable | length == 0'"
ok "dashboard aggregates all services"

step "Catalog data reached inventory-service over Kafka (event-carried state transfer)"
retry 120 bash -c "curl -fsS '$GW/api/inventory/SKU-3002' | jq -e '.[0].product.supplier.name == \"Initech Electronics\"'"
ok "inventory renders supplier details it never queried catalog-service for"

step "Delivery saga: shipment-service -> order-service -> inventory-service"
before=$(qty SKU-1002 WH-EAST)
po=$(json -X POST "$GW/api/orders" -d '{"sku":"SKU-1002","warehouseCode":"WH-EAST","quantity":250}' | jq -r .orderNumber)
ok "created $po (order-service looked up the product in catalog-service)"
eta=$(date -u -d '+2 days' +%Y-%m-%dT%H:%M:%SZ)
trk=$(json -X POST "$GW/api/shipments" \
  -d "{\"orderNumber\":\"$po\",\"carrier\":\"FedEx\",\"origin\":\"Columbus, OH\",\"destination\":\"WH-EAST\",\"eta\":\"$eta\"}" \
  | jq -r .trackingNumber)
ok "created shipment $trk (shipment-service verified the order with order-service)"

json -X PATCH "$GW/api/shipments/$trk/status" -d '{"status":"IN_TRANSIT"}' >/dev/null
retry 60 bash -c "curl -fsS '$GW/api/orders/$po' | jq -e '.status == \"SHIPPED\"'"
ok "SHIPMENT_IN_TRANSIT -> order $po is SHIPPED"

json -X PATCH "$GW/api/shipments/$trk/status" -d '{"status":"DELIVERED"}' >/dev/null
retry 60 bash -c "curl -fsS '$GW/api/orders/$po' | jq -e '.status == \"RECEIVED\"'"
ok "SHIPMENT_DELIVERED -> order $po is RECEIVED"
expected=$((before + 250))
retry 60 bash -c "[ \"\$(curl -fsS '$GW/api/inventory/SKU-1002' | jq '.[] | select(.warehouseCode == \"WH-EAST\") | .quantity')\" = '$expected' ]"
ok "ORDER_RECEIVED -> stock of SKU-1002@WH-EAST went $before -> $expected"

step "Flink detects low stock from the inventory event stream"
retry 180 bash -c "curl -fsS '$FLINK/jobs/overview' | jq -e '.jobs[] | select(.state == \"RUNNING\")'"
ok "Flink job is running"
current=$(qty SKU-3002 WH-EAST)
json -X POST "$GW/api/inventory/adjust" \
  -d "{\"sku\":\"SKU-3002\",\"warehouseCode\":\"WH-EAST\",\"delta\":$((30 - current)),\"reason\":\"e2e\"}" >/dev/null
low_stock_alert() {
  curl -fsS "$GW/api/alerts" | jq -e '.[] | select(.type == "LOW_STOCK" and .entityId == "SKU-3002" and .source == "scm-stream-processor")'
}
for _ in $(seq 1 12); do
  low_stock_alert >/dev/null 2>&1 && break
  # The job may have started reading after the first event; another still-low update re-triggers it.
  json -X POST "$GW/api/inventory/adjust" -d '{"sku":"SKU-3002","warehouseCode":"WH-EAST","delta":-1,"reason":"e2e"}' >/dev/null
  sleep 10
done
retry 30 low_stock_alert
ok "LOW_STOCK alert went Flink -> scm.alerts -> alert-service -> gateway"

step "Resilience: order-service goes down"
docker compose stop order-service >/dev/null
status=$(curl -s -o /dev/null -w '%{http_code}' "$GW/api/orders")
[ "$status" = 503 ] || { echo "   expected 503 from gateway fallback, got $status"; exit 1; }
ok "gateway answers /api/orders with a 503 fallback"
curl -fsS "$GW/api/dashboard" | jq -e '(.unavailable | index("order-service")) and .skus != null' >/dev/null
ok "dashboard still renders, listing order-service as unavailable"
docker compose start order-service >/dev/null
retry 180 bash -c "curl -fsS '$GW/api/orders' | jq -e 'length > 0'"
ok "order-service recovered"

step "Distributed tracing reached Jaeger"
retry 60 bash -c "curl -fsS '$JAEGER/api/services' | jq -e '.data | index(\"api-gateway\") and index(\"order-service\") and index(\"inventory-service\")'"
ok "spans from gateway, order-service and inventory-service are in Jaeger"

printf '\nAll end-to-end checks passed.\n'
