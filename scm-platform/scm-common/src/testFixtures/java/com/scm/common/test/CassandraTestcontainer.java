package com.scm.common.test;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.cassandra.CassandraContainer;

/**
 * One Cassandra node per test JVM, shared by every Spring test context of the module (as the in-memory
 * database was before), so test classes with different properties don't each start their own.
 * Use with {@code @Import(CassandraTestcontainer.class)}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class CassandraTestcontainer {

    private static final CassandraContainer CASSANDRA = new CassandraContainer("cassandra:5.0.5");

    /** Not stopped with a context, since other contexts still use it; Testcontainers removes it at JVM exit. */
    @Bean(destroyMethod = "")
    @ServiceConnection
    public CassandraContainer cassandraContainer() {
        return CASSANDRA;
    }
}
