package com.scm.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "scm")
public record ScmProperties(Kafka kafka, Topics topics, Feeds feeds, Cors cors) {

    public record Kafka(boolean enabled) {}

    public record Topics(String inventory, String orders, String shipments, String alerts, String vision) {}

    public record Feeds(String inbox) {}

    public record Cors(String allowedOrigins) {}
}
