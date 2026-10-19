description = "Owns purchase orders; orchestrates its part of the delivery saga"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-data-cassandra")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.cloud:spring-cloud-starter-circuitbreaker-resilience4j")
    testImplementation(testFixtures(project(":scm-common")))
    testImplementation("org.springframework.kafka:spring-kafka-test")
}
