package com.scm.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreakerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.StreamSupport;

/**
 * API composition: the dashboard needs data owned by four services, so the gateway fans out in
 * parallel and aggregates. Each call has its own circuit breaker; if a service is down the
 * dashboard still renders, with that service listed under "unavailable".
 */
@RestController
public class DashboardController {

    public record Summary(Long skus, Long unitsOnHand, Long lowStockItems, Long openOrders, Long shipmentsInTransit,
                          Long delayedShipments, Long recentAlerts, List<String> unavailable) {}

    private final WebClient web;
    private final ReactiveCircuitBreakerFactory<?, ?> breakers;
    private final ServiceUrls urls;

    public DashboardController(WebClient.Builder web, ReactiveCircuitBreakerFactory<?, ?> breakers, ServiceUrls urls) {
        this.web = web.build();
        this.breakers = breakers;
        this.urls = urls;
    }

    @GetMapping("/api/dashboard")
    public Mono<Summary> summary() {
        return Mono.zip(
                fetch("inventory-service", urls.inventory() + "/api/inventory"),
                fetch("order-service", urls.orders() + "/api/orders"),
                fetch("shipment-service", urls.shipments() + "/api/shipments"),
                fetch("shipment-service", urls.shipments() + "/api/shipments/delayed"),
                fetch("alert-service", urls.alerts() + "/api/alerts")
        ).map(r -> {
            List<String> unavailable = new ArrayList<>();
            Optional<List<JsonNode>> inventory = r.getT1().list(unavailable);
            Optional<List<JsonNode>> orders = r.getT2().list(unavailable);
            Optional<List<JsonNode>> shipments = r.getT3().list(unavailable);
            Optional<List<JsonNode>> delayed = r.getT4().list(unavailable);
            Optional<List<JsonNode>> alerts = r.getT5().list(unavailable);
            return new Summary(
                    inventory.map(l -> l.stream().map(i -> i.path("product").path("sku").asText()).distinct().count()).orElse(null),
                    inventory.map(l -> l.stream().mapToLong(i -> i.path("quantity").asLong()).sum()).orElse(null),
                    inventory.map(l -> l.stream().filter(i -> i.path("lowStock").asBoolean()).count()).orElse(null),
                    orders.map(l -> l.stream().filter(o -> List.of("CREATED", "APPROVED", "SHIPPED")
                            .contains(o.path("status").asText())).count()).orElse(null),
                    shipments.map(l -> l.stream().filter(s -> "IN_TRANSIT".equals(s.path("status").asText())).count()).orElse(null),
                    delayed.map(l -> (long) l.size()).orElse(null),
                    alerts.map(l -> (long) l.size()).orElse(null),
                    unavailable.stream().distinct().toList());
        });
    }

    /** Result of one downstream call: the JSON array, or the name of the service that failed. */
    private record Part(String service, JsonNode body) {
        Optional<List<JsonNode>> list(List<String> unavailable) {
            if (body == null) {
                unavailable.add(service);
                return Optional.empty();
            }
            return Optional.of(StreamSupport.stream(body.spliterator(), false).toList());
        }
    }

    private Mono<Part> fetch(String service, String url) {
        Mono<Part> call = web.get().uri(url).retrieve().bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(5))
                .map(body -> new Part(service, body));
        return breakers.create("dashboard-" + service).run(call, failure -> Mono.just(new Part(service, null)));
    }
}
