package com.scm.common.test;

import com.datastax.oss.driver.api.core.CqlSession;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.cassandra.CassandraContainer;

/**
 * One Cassandra node per test JVM, shared by every Spring test context of the module (as the in-memory
 * database was before), so test classes with different properties don't each start their own.
 * Use with {@code @Import(CassandraTestcontainer.class)}.
 * <p>
 * Without Docker, the tests use the Cassandra on {@code localhost:9042} (e.g. in WSL), in a keyspace of
 * their own that is dropped afterwards, so they start from the seed data and never touch dev data.
 */
@TestConfiguration(proxyBeanMethods = false)
public class CassandraTestcontainer {

    private static final CassandraContainer CASSANDRA = new CassandraContainer("cassandra:5.0.5");

    /** Shared by the contexts of one test JVM, unique per run. */
    private static final String RUN = Long.toString(System.currentTimeMillis(), 36);

    /** Not stopped with a context, since other contexts still use it; Testcontainers removes it at JVM exit. */
    @Bean(destroyMethod = "")
    @ServiceConnection
    @Conditional(Docker.Available.class)
    public CassandraContainer cassandraContainer() {
        return CASSANDRA;
    }

    @Bean
    @Conditional(Docker.Unavailable.class)
    public DynamicPropertyRegistrar localTestKeyspace(Environment environment) {
        String keyspace = "test_" + environment.getRequiredProperty("spring.cassandra.keyspace-name") + "_" + RUN;
        return registry -> registry.add("spring.cassandra.keyspace-name", () -> keyspace);
    }

    @Bean
    @Conditional(Docker.Unavailable.class)
    public DisposableBean dropLocalTestKeyspace(CqlSession session) {
        return () -> session.getKeyspace().ifPresent(keyspace ->
                session.execute("DROP KEYSPACE IF EXISTS " + keyspace.asCql(true)));
    }
}
