package com.scm.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Downstream base URLs; the same values back the routes in application.yml. */
@ConfigurationProperties(prefix = "scm.services")
public record ServiceUrls(String catalog, String inventory, String orders, String shipments, String alerts) {
}
