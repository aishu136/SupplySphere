package com.scm.shipment;

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

/** Checks a purchase order exists before a shipment is booked against it (circuit-breaker guarded). */
@Component
public class OrderClient {

    private final RestClient rest;
    private final CircuitBreaker breaker;

    public OrderClient(RestClient.Builder builder, CircuitBreakerFactory<?, ?> breakers,
                       @Value("${scm.services.order-url}") String orderUrl) {
        SimpleClientHttpRequestFactory timeouts = new SimpleClientHttpRequestFactory();
        timeouts.setConnectTimeout(Duration.ofSeconds(2));
        timeouts.setReadTimeout(Duration.ofSeconds(3));
        this.rest = builder.baseUrl(orderUrl).requestFactory(timeouts).build();
        this.breaker = breakers.create("orders");
    }

    public void requireOrder(String orderNumber) {
        breaker.run(() -> {
            try {
                rest.get().uri("/api/orders/{n}", orderNumber).retrieve().toBodilessEntity();
                return null;
            } catch (HttpClientErrorException.NotFound e) {
                throw new NotFoundException("Purchase order " + orderNumber + " not found");
            }
        }, failure -> {
            Throwable cause = failure;
            while ((cause instanceof ExecutionException || cause instanceof CompletionException) && cause.getCause() != null) {
                cause = cause.getCause();
            }
            if (cause instanceof NotFoundException notFound) {
                throw notFound;
            }
            throw new ServiceUnavailableException("order-service is unavailable: " + cause.getMessage(), cause);
        });
    }
}
