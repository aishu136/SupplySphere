package com.scm.order;

import com.scm.common.catalog.ProductView;
import com.scm.common.web.NotFoundException;
import com.scm.common.web.ServiceUnavailableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * Synchronous call to catalog-service when a PO is placed: the order must capture the current
 * supplier, price and lead time. Guarded by a circuit breaker so a slow or down catalog fails
 * fast with 503 instead of tying up order-service threads.
 */
@Component
public class CatalogClient {

    private final RestClient rest;
    private final CircuitBreaker breaker;

    public CatalogClient(RestClient.Builder builder, CircuitBreakerFactory<?, ?> breakers,
                         @Value("${scm.services.catalog-url}") String catalogUrl) {
        SimpleClientHttpRequestFactory timeouts = new SimpleClientHttpRequestFactory();
        timeouts.setConnectTimeout(Duration.ofSeconds(2));
        timeouts.setReadTimeout(Duration.ofSeconds(3));
        this.rest = builder.baseUrl(catalogUrl).requestFactory(timeouts).build();
        this.breaker = breakers.create("catalog");
    }

    public ProductView product(String sku) {
        return breaker.run(() -> {
            try {
                return rest.get().uri("/api/products/{sku}", sku).retrieve().body(ProductView.class);
            } catch (HttpClientErrorException.NotFound e) {
                throw new NotFoundException("Product " + sku + " not found");
            }
        }, failure -> {
            Throwable cause = unwrap(failure);
            if (cause instanceof NotFoundException notFound) {
                throw notFound;
            }
            throw new ServiceUnavailableException("catalog-service is unavailable: " + cause.getMessage(), cause);
        });
    }

    private static Throwable unwrap(Throwable t) {
        while ((t instanceof ExecutionException || t instanceof CompletionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }
}
