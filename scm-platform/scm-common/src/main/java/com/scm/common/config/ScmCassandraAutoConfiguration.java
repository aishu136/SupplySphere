package com.scm.common.config;

import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import com.datastax.oss.driver.api.querybuilder.SchemaBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.cassandra.CassandraAutoConfiguration;
import org.springframework.boot.autoconfigure.cassandra.CassandraProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Database per service on Cassandra: each service owns one keyspace ({@code spring.cassandra.keyspace-name})
 * and creates it on startup if it is missing, as its tables are created from the entities
 * ({@code spring.cassandra.schema-action}). Replication defaults to 1 for the single-node dev cluster.
 */
@AutoConfiguration(before = CassandraAutoConfiguration.class)
@ConditionalOnClass(CqlSession.class)
@ConditionalOnProperty("spring.cassandra.keyspace-name")
public class ScmCassandraAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public CqlSession cassandraSession(ObjectProvider<CqlSessionBuilder> builders, CassandraProperties properties,
                                       @Value("${scm.cassandra.replication-factor:1}") int replicationFactor) {
        CqlIdentifier keyspace = CqlIdentifier.fromCql(properties.getKeyspaceName());
        // Boot's session builder is a prototype bound to the keyspace; this first one is unbound, to create it.
        try (CqlSession admin = builders.getObject().withKeyspace((CqlIdentifier) null).build()) {
            admin.execute(SchemaBuilder.createKeyspace(keyspace).ifNotExists()
                    .withSimpleStrategy(replicationFactor).build());
        }
        return builders.getObject().withKeyspace(keyspace).build();
    }
}
