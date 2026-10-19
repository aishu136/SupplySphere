description = "Owns suppliers and products; broadcasts them on the compacted scm.catalog.events topic"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-data-cassandra")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-cache")
    testImplementation(testFixtures(project(":scm-common")))
    testImplementation("org.springframework.kafka:spring-kafka-test")
}
