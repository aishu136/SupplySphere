description = "Owns shipments and inspections; starts the delivery saga; routes vision results with Apache Camel"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-data-cassandra")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.cloud:spring-cloud-starter-circuitbreaker-resilience4j")
    implementation("org.apache.camel.springboot:camel-spring-boot-starter")
    implementation("org.apache.camel.springboot:camel-kafka-starter")
    implementation("org.apache.camel.springboot:camel-jackson-starter")
    implementation("org.apache.camel.springboot:camel-bean-starter")
    testImplementation(testFixtures(project(":scm-common")))
    testImplementation("org.springframework.kafka:spring-kafka-test")
}
