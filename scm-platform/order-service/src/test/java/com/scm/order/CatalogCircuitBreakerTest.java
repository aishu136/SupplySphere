package com.scm.order;

import com.scm.common.web.ServiceUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** catalog-service is down (nothing listens on port 1): placing an order fails fast with 503. */
@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "scm.services.catalog-url=http://localhost:1",
        "management.tracing.enabled=false"})
@EmbeddedKafka(partitions = 1)
class CatalogCircuitBreakerTest {

    @Autowired OrderService orderService;

    @Test
    void catalogOutageSurfacesAsServiceUnavailable() {
        assertThatThrownBy(() -> orderService.create("SKU-1001", "WH-EAST", 10))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("catalog-service is unavailable");
    }
}
