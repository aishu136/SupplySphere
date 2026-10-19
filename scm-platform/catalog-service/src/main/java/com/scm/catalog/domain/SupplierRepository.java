package com.scm.catalog.domain;

import org.springframework.data.cassandra.repository.CassandraRepository;

public interface SupplierRepository extends CassandraRepository<Supplier, Long> {
}
