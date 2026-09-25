description = "Owns stock levels per SKU and warehouse; ingests supplier stock feeds with Apache Camel"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.apache.camel.springboot:camel-spring-boot-starter")
    implementation("org.apache.camel.springboot:camel-file-starter")
    implementation("org.apache.camel.springboot:camel-bean-starter")
    implementation("org.apache.camel.springboot:camel-csv-starter")
    runtimeOnly("com.h2database:h2")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.kafka:spring-kafka-test")
}
