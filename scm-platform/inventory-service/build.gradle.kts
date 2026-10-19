description = "Owns stock levels per SKU and warehouse; ingests supplier stock feeds with Apache Camel"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-data-cassandra")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.apache.camel.springboot:camel-spring-boot-starter")
    implementation("org.apache.camel.springboot:camel-file-starter")
    implementation("org.apache.camel.springboot:camel-bean-starter")
    implementation("org.apache.camel.springboot:camel-csv-starter")
    testImplementation(testFixtures(project(":scm-common")))
    testImplementation("org.springframework.kafka:spring-kafka-test")
}
