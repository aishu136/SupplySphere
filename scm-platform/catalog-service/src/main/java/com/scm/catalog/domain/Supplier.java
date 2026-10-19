package com.scm.catalog.domain;

import com.scm.common.catalog.SupplierView;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

@Table("suppliers")
public class Supplier {

    /** Assigned by the catalog (Cassandra has no sequences); other services' data refers to it. */
    @PrimaryKey
    private Long id;

    private String name;
    private String contactEmail;
    private String country;
    private double rating;
    private int leadTimeDays;

    protected Supplier() {}

    public Supplier(Long id, String name, String contactEmail, String country, double rating, int leadTimeDays) {
        this.id = id;
        this.name = name;
        this.contactEmail = contactEmail;
        this.country = country;
        this.rating = rating;
        this.leadTimeDays = leadTimeDays;
    }

    public SupplierView toView() {
        return new SupplierView(id, name, contactEmail, country, rating, leadTimeDays);
    }

    public Long getId() { return id; }
}
