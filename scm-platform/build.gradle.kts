plugins {
    id("org.springframework.boot") version "3.5.6" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}

val springCloudVersion = "2025.0.0"
val camelVersion = "4.14.0"

allprojects {
    group = "com.scm"
    version = "1.0.0"
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "io.spring.dependency-management")

    repositories {
        mavenCentral()
    }

    extensions.configure<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension> {
        imports {
            mavenBom(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)
            mavenBom("org.springframework.cloud:spring-cloud-dependencies:$springCloudVersion")
            mavenBom("org.apache.camel.springboot:camel-spring-boot-bom:$camelVersion")
        }
    }

    // Shared by every module: observability (metrics, OpenTelemetry tracing) and tests.
    dependencies {
        "implementation"("org.springframework.boot:spring-boot-starter-actuator")
        "implementation"("io.micrometer:micrometer-tracing-bridge-otel")
        "implementation"("io.opentelemetry:opentelemetry-exporter-otlp")
        "testImplementation"("org.springframework.boot:spring-boot-starter-test")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release = 21
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}

// Every module except scm-common is a runnable Spring Boot service, packaged as build/libs/<service>-1.0.0.jar.
configure(subprojects.filter { it.name != "scm-common" }) {
    apply(plugin = "org.springframework.boot")

    // The reactive gateway stays off scm-common, which brings the servlet stack.
    if (name != "api-gateway") {
        dependencies {
            "implementation"(project(":scm-common"))
        }
    }

    // Only the executable jar, so the Dockerfile can copy build/libs/<service>-*.jar unambiguously.
    tasks.named<Jar>("jar") {
        enabled = false
    }
}
