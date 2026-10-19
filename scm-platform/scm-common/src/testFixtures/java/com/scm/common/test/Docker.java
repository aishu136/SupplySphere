package com.scm.common.test;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.testcontainers.DockerClientFactory;

/**
 * Whether the tests can start containers. Without Docker, they use the Cassandra and Redis running on
 * localhost instead (e.g. in WSL; see the README).
 */
final class Docker {

    private Docker() {}

    static boolean available() {
        return DockerClientFactory.instance().isDockerAvailable();
    }

    static final class Available implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return available();
        }
    }

    static final class Unavailable implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return !available();
        }
    }
}
