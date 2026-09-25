description = "Collects alerts from scm.alerts (Flink, services) and streams them to UIs over SSE"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-validation")
    testImplementation("org.springframework.kafka:spring-kafka-test")
}
