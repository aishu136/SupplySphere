package com.scm.common.catalog;

public record SupplierView(Long id, String name, String contactEmail, String country, double rating,
                           int leadTimeDays) {
}
