package com.scm.core.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "suppliers")
public class Supplier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String contactEmail;
    private String country;
    private double rating;
    private int leadTimeDays;

    protected Supplier() {}

    public Supplier(String name, String contactEmail, String country, double rating, int leadTimeDays) {
        this.name = name;
        this.contactEmail = contactEmail;
        this.country = country;
        this.rating = rating;
        this.leadTimeDays = leadTimeDays;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getContactEmail() { return contactEmail; }
    public String getCountry() { return country; }
    public double getRating() { return rating; }
    public int getLeadTimeDays() { return leadTimeDays; }
}
