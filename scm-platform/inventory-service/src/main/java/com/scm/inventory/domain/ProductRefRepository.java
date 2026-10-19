package com.scm.inventory.domain;

import org.springframework.data.cassandra.repository.CassandraRepository;

public interface ProductRefRepository extends CassandraRepository<ProductRef, String> {
}
