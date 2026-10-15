package com.scm.common.catalog;

import java.util.Locale;
import java.util.Map;

/**
 * Storage temperature a product must be kept at through the cold chain. A product without one is
 * ambient. {@code profile} is FROZEN, CHILLED or COOL; the limits default to the profile's range but
 * can be narrowed or widened per product (e.g. ice cream at -25 °C or colder).
 */
public record TemperatureRange(String profile, double minC, double maxC) {

    public static final Map<String, TemperatureRange> PROFILES = Map.of(
            "FROZEN", new TemperatureRange("FROZEN", -30, -18),
            "CHILLED", new TemperatureRange("CHILLED", 0, 4),
            "COOL", new TemperatureRange("COOL", 8, 15));

    /** Validated range: the profile's defaults, with either limit overridden when given. */
    public static TemperatureRange of(String profile, Double minC, Double maxC) {
        if (profile == null) {
            throw new IllegalArgumentException("profile is required (one of " + String.join(", ", PROFILES.keySet()) + ")");
        }
        TemperatureRange defaults = PROFILES.get(profile.trim().toUpperCase(Locale.ROOT));
        if (defaults == null) {
            throw new IllegalArgumentException("Unknown profile " + profile + " (use one of "
                    + String.join(", ", PROFILES.keySet()) + ")");
        }
        double min = minC != null ? minC : defaults.minC();
        double max = maxC != null ? maxC : defaults.maxC();
        if (min >= max) {
            throw new IllegalArgumentException("minC must be below maxC");
        }
        if (min < -60 || max > 30) {
            throw new IllegalArgumentException("Cold-chain limits must be between -60 and 30 °C");
        }
        return new TemperatureRange(defaults.profile(), min, max);
    }
}
