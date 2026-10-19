package com.scm.shipment;

import com.scm.shipment.domain.Shipment;
import com.scm.shipment.domain.ShipmentRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Demo shipments for order-service's seeded purchase orders; TRK-DEMO000002 is already overdue.
 * TRK-DEMO000004 and TRK-DEMO000005 are reefer loads of frozen food for cold-chain inspection.
 */
@Component
public class DataSeeder implements CommandLineRunner {

    private final ShipmentRepository shipments;

    public DataSeeder(ShipmentRepository shipments) {
        this.shipments = shipments;
    }

    @Override
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

        Shipment salmon = new Shipment("TRK-DEMO000004", "PO-DEMO0004", "Polar Freight (reefer)", "Bergen, NO",
                "Newark, NJ", now.plus(Duration.ofDays(1)));
        salmon.setStatus(Shipment.Status.IN_TRANSIT);
        Shipment iceCream = new Shipment("TRK-DEMO000005", "PO-DEMO0005", "Polar Freight (reefer)", "Rotterdam, NL",
                "Reno, NV", now.plus(Duration.ofDays(2)));
        iceCream.setStatus(Shipment.Status.IN_TRANSIT);
        shipments.save(salmon);
        shipments.save(iceCream);
    }
}
