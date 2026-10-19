package com.scm.common.test;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;

/** One Redis per test JVM, for services that cache; shared like {@link CassandraTestcontainer}. */
@TestConfiguration(proxyBeanMethods = false)
public class RedisTestcontainer {

    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    @Bean(destroyMethod = "")
    @ServiceConnection(name = "redis")
    public GenericContainer<?> redisContainer() {
        return REDIS;
    }
}
