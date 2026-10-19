plugins {
    `java-library`
    `java-test-fixtures`
}

description = "Shared event contract, Kafka publishing, Cassandra keyspaces, Redis caching, error handling and resilience defaults"

dependencies {
    api("org.springframework.boot:spring-boot-starter-web")
    api("org.springframework.kafka:spring-kafka")
    api("org.springframework:spring-tx")
    // Optional: each applies only to services that bring the starter themselves.
    compileOnly("org.springframework.cloud:spring-cloud-starter-circuitbreaker-resilience4j")
    compileOnly("org.springframework.boot:spring-boot-starter-data-cassandra")
    compileOnly("org.springframework.boot:spring-boot-starter-data-redis")

    // Shared setup for the services' integration tests: Testcontainers, or local Cassandra/Redis without Docker.
    testFixturesApi("org.springframework.boot:spring-boot-testcontainers")
    testFixturesApi("org.testcontainers:cassandra")
    testFixturesImplementation("org.springframework.boot:spring-boot-test")
    testFixturesImplementation("org.springframework:spring-test")
    testFixturesCompileOnly("org.apache.cassandra:java-driver-core")
    testFixturesCompileOnly("org.springframework.data:spring-data-redis")
}
