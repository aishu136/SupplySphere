package com.scm.inventory.domain;

import org.springframework.data.cassandra.repository.CassandraRepository;

public interface ProcessedEventRepository extends CassandraRepository<ProcessedEvent, String> {
}
