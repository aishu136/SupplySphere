package com.scm.catalog.domain;

import org.springframework.data.cassandra.repository.CassandraRepository;

/** Keyed by SKU, the catalog's business key: every lookup is a single-partition read. */
public interface ProductRepository extends CassandraRepository<Product, String> {
}
