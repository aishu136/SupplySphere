package com.scm.inventory;

import com.scm.common.catalog.ProductView;
import com.scm.common.catalog.SupplierView;
import com.scm.common.catalog.TemperatureRange;
import com.scm.inventory.domain.ProductRef;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ProductRefTest {

    private static final SupplierView NORDIC = new SupplierView(4L, "Nordic Cold Foods", "orders@nordiccold.example",
            "Norway", 4.4, 5);

    @Test
    void readModelKeepsTheTemperatureRange() {
        ProductRef ref = new ProductRef("SKU-4003");
        ref.update(new ProductView(9L, "SKU-4003", "Ice cream tubs 4 x 5 L", "Frozen food", new BigDecimal("46.00"),
                NORDIC, new TemperatureRange("FROZEN", -30, -25)));
        assertThat(ref.toView().temperatureRange()).isEqualTo(new TemperatureRange("FROZEN", -30, -25));

        // A later event without a range (product made ambient) clears it.
        ref.update(new ProductView(9L, "SKU-4003", "Ice cream tubs 4 x 5 L", "Frozen food", new BigDecimal("46.00"), NORDIC));
        assertThat(ref.toView().temperatureRange()).isNull();
    }
}
