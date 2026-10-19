package com.scm.order.domain;

import org.springframework.data.cassandra.repository.CassandraRepository;

/** Keyed by order number, the business key other services use. */
public interface PurchaseOrderRepository extends CassandraRepository<PurchaseOrder, String> {
}
