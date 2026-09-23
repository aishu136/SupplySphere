package com.scm.shipment;

import com.scm.shipment.domain.Shipment;
import com.scm.shipment.domain.ShipmentRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/** Demo shipments for order-service's seeded purchase orders; TRK-DEMO000002 is already overdue. */
@Component
public class DataSeeder implements CommandLineRunner {

    private final ShipmentRepository shipments;

    public DataSeeder(ShipmentRepository shipments) {
        this.shipments = shipments;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (shipments.count() > 0) {
            return;
        }
        Instant now = Instant.now();
        Shipment cartons = new Shipment("TRK-DEMO000001", "PO-DEMO0001", "DHL", "Hamburg, DE", "Reno, NV",
                now.plus(Duration.ofDays(3)));
        cartons.setStatus(Shipment.Status.IN_TRANSIT);
        Shipment sensors = new Shipment("TRK-DEMO000002", "PO-DEMO0002", "Maersk", "Kaohsiung, TW", "Newark, NJ",
                now.minus(Duration.ofHours(30)));
        sensors.setStatus(Shipment.Status.IN_TRANSIT);
        shipments.save(cartons);
        shipments.save(sensors);
        shipments.save(new Shipment("TRK-DEMO000003", null, "UPS", "Newark, NJ", "Boston, MA", now.plus(Duration.ofDays(1))));
    }
}
