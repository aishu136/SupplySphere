package com.scm.common.cache;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.cache.CacheAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

import java.time.Duration;

/**
 * Shared Redis cache policy for services that enable caching: entries expire after {@code scm.cache.ttl}
 * (a safety net; services evict on change), keys are prefixed with the service name because the services
 * share one Redis, and nulls are not cached. A cache is an optimization, so when Redis is unreachable,
 * reads are logged and fall through to the database instead of failing the request.
 */
@AutoConfiguration(before = CacheAutoConfiguration.class)
@ConditionalOnClass({RedisConnectionFactory.class, CachingConfigurer.class})
public class ScmCacheAutoConfiguration implements CachingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(ScmCacheAutoConfiguration.class);

    @Bean
    public RedisCacheConfiguration scmRedisCacheConfiguration(@Value("${spring.application.name}") String service,
                                                              @Value("${scm.cache.ttl:10m}") Duration ttl) {
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(ttl)
                .disableCachingNullValues()
                .computePrefixWith(cacheName -> service + "::" + cacheName + "::");
    }

    /** Values as plain JSON of one known type (e.g. a view record), so no class names end up in Redis. */
    public static RedisCacheConfiguration json(RedisCacheConfiguration defaults, ObjectMapper objectMapper,
                                               JavaType type) {
        return defaults.serializeValuesWith(SerializationPair.fromSerializer(
                new Jackson2JsonRedisSerializer<>(objectMapper, type)));
    }

    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException e, Cache cache, Object key) {
                log.warn("Cache {} unavailable for get of {}; reading from the database", cache.getName(), key, e);
            }

            @Override
            public void handleCachePutError(RuntimeException e, Cache cache, Object key, Object value) {
                log.warn("Cache {} unavailable for put of {}", cache.getName(), key, e);
            }

            @Override
            public void handleCacheEvictError(RuntimeException e, Cache cache, Object key) {
                log.error("Cache {} unavailable for evict of {}; entry may be stale until its TTL",
                        cache.getName(), key, e);
            }

            @Override
            public void handleCacheClearError(RuntimeException e, Cache cache) {
                log.error("Cache {} unavailable for clear; entries may be stale until their TTL", cache.getName(), e);
            }
        };
    }
}
