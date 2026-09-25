plugins {
    java
    id("com.gradleup.shadow") version "9.2.2"
}

group = "com.scm"
version = "1.0.0"
description = "Apache Flink job: real-time low-stock, shipment-delay and demand-spike detection"

val flinkVersion = "1.20.1"
val flinkKafkaConnectorVersion = "3.3.0-1.20"
val jacksonVersion = "2.17.2"
val junitVersion = "5.10.3"

repositories {
    mavenCentral()
}

// Provided by the Flink runtime: compiled and tested against, never bundled.
val provided: Configuration by configurations.creating
configurations.compileOnly { extendsFrom(provided) }
configurations.testImplementation { extendsFrom(provided) }

dependencies {
    provided("org.apache.flink:flink-streaming-java:$flinkVersion")
    provided("org.apache.flink:flink-clients:$flinkVersion")
    provided("org.slf4j:slf4j-api:1.7.36")

    // Bundled into the job jar.
    // Keep jackson-core/annotations in step with jackson-databind: a stale transitive
    // jackson-core crashed the job on its first alert (NoSuchMethodError).
    implementation(enforcedPlatform("com.fasterxml.jackson:jackson-bom:$jacksonVersion"))
    implementation("org.apache.flink:flink-connector-base:$flinkVersion")
    implementation("org.apache.flink:flink-connector-kafka:$flinkKafkaConnectorVersion")
    implementation("com.fasterxml.jackson.core:jackson-databind")

    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
}

// The job jar: build/libs/scm-stream-processor-1.0.0.jar (what docker-compose submits to Flink).
tasks.shadowJar {
    archiveClassifier = ""
    configurations = listOf(project.configurations.runtimeClasspath.get())
    manifest {
        attributes["Main-Class"] = "com.scm.stream.SupplyChainAlertJob"
    }
    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    dependencies {
        exclude(dependency("org.apache.flink:flink-shaded-force-shading"))
        exclude(dependency("com.google.code.findbugs:jsr305"))
        exclude(dependency("org.slf4j:.*"))
        exclude(dependency("org.apache.logging.log4j:.*"))
    }
}

tasks.jar {
    enabled = false
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}
