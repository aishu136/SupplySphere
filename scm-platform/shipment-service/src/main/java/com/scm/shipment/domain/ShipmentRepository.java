package com.scm.shipment.domain;

import org.springframework.data.cassandra.repository.CassandraRepository;

/** Keyed by tracking number, the business key carriers, the vision pipeline and other services use. */
public interface ShipmentRepository extends CassandraRepository<Shipment, String> {
}
