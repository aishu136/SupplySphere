package com.scm.common.test;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;

/**
 * One Redis per test JVM, for services that cache; shared like {@link CassandraTestcontainer}.
 * Without Docker, the tests use the Redis on {@code localhost:6379} (e.g. in WSL), in a database of their
 * own (15) that is emptied first, so no entry from an earlier run or from dev use is read.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RedisTestcontainer {

    private static final int LOCAL_TEST_DATABASE = 15;

    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    @Bean(destroyMethod = "")
    @ServiceConnection(name = "redis")
    @Conditional(Docker.Available.class)
    public GenericContainer<?> redisContainer() {
        return REDIS;
    }

    @Bean
    @Conditional(Docker.Unavailable.class)
    public DynamicPropertyRegistrar localTestRedisDatabase() {
        return registry -> registry.add("spring.data.redis.database", () -> LOCAL_TEST_DATABASE);
    }

    @Bean
    @Conditional(Docker.Unavailable.class)
    public InitializingBean flushLocalTestRedis(RedisConnectionFactory connections) {
        return () -> {
            try (RedisConnection connection = connections.getConnection()) {
                connection.serverCommands().flushDb();
            }
        };
    }
}
