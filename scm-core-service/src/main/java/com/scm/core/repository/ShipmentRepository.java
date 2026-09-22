package com.scm.core.repository;

import com.scm.core.domain.Shipment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ShipmentRepository extends JpaRepository<Shipment, Long> {

    Optional<Shipment> findByTrackingNumber(String trackingNumber);

    List<Shipment> findAllByOrderByEtaAsc();

    /** Shipments that are delayed, or still moving past their ETA. */
    @Query("""
            select s from Shipment s
            where s.status = com.scm.core.domain.Shipment.Status.DELAYED
               or (s.status in (com.scm.core.domain.Shipment.Status.CREATED, com.scm.core.domain.Shipment.Status.IN_TRANSIT)
                   and s.eta < :now)
            order by s.eta asc
            """)
    List<Shipment> findDelayed(Instant now);
}
