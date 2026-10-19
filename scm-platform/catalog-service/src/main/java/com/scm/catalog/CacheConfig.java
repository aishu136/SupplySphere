package com.scm.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.scm.common.catalog.ProductView;
import com.scm.common.catalog.SupplierView;
import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;

import java.util.List;

import static com.scm.common.cache.ScmCacheAutoConfiguration.json;

/** Redis caches of {@link CatalogService}, each holding JSON of one view type. */
@Configuration(proxyBeanMethods = false)
@EnableCaching
class CacheConfig {

    @Bean
    RedisCacheManagerBuilderCustomizer catalogCaches(RedisCacheConfiguration defaults, ObjectMapper objectMapper) {
        TypeFactory types = objectMapper.getTypeFactory();
        return builder -> builder
                .withCacheConfiguration(CatalogService.PRODUCT,
                        json(defaults, objectMapper, types.constructType(ProductView.class)))
                .withCacheConfiguration(CatalogService.PRODUCTS,
                        json(defaults, objectMapper, types.constructCollectionType(List.class, ProductView.class)))
                .withCacheConfiguration(CatalogService.SUPPLIER,
                        json(defaults, objectMapper, types.constructType(SupplierView.class)))
                .withCacheConfiguration(CatalogService.SUPPLIERS,
                        json(defaults, objectMapper, types.constructCollectionType(List.class, SupplierView.class)))
                // An unregistered cache name is a bug, not a cache to create with Java serialization.
                .disableCreateOnMissingCache();
    }
}
