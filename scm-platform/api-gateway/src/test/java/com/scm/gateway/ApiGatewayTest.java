package com.scm.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Every downstream points at a closed port, i.e. all services are down. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "CATALOG_URL=http://localhost:1", "INVENTORY_URL=http://localhost:1", "ORDER_URL=http://localhost:1",
        "SHIPMENT_URL=http://localhost:1", "ALERT_URL=http://localhost:1",
        "management.tracing.enabled=false"})
class ApiGatewayTest {

    @Autowired WebTestClient client;
    @Autowired RouteLocator routes;

    @Test
    void routesEveryDomainApi() {
        List<String> ids = routes.getRoutes().map(Route::getId).collectList().block();
        assertThat(ids).contains("catalog", "inventory", "orders", "shipments", "alerts", "alerts-stream");
    }

    @Test
    void downServiceGetsFallback503InsteadOfAnError() {
        client.get().uri("/api/inventory").exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.service").isEqualTo("inventory-service");
    }

    @Test
    void dashboardDegradesGracefullyWhenServicesAreDown() {
        client.get().uri("/api/dashboard").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.unavailable.length()").isEqualTo(4)
                .jsonPath("$.skus").doesNotExist();
    }
}
