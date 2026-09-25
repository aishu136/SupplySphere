plugins {
    `java-library`
}

description = "Shared event contract, Kafka publishing (after commit), error handling and resilience defaults"

dependencies {
    api("org.springframework.boot:spring-boot-starter-web")
    api("org.springframework.kafka:spring-kafka")
    api("org.springframework:spring-tx")
    // Optional: the resilience defaults apply only to services that bring the circuit breaker themselves.
    compileOnly("org.springframework.cloud:spring-cloud-starter-circuitbreaker-resilience4j")
}
