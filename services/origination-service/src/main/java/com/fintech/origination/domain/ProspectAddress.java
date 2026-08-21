package com.fintech.origination.domain;

import jakarta.persistence.Embeddable;

@Embeddable
public class ProspectAddress {

    private String street;
    private String exteriorNumber;
    private String interiorNumber;
    private String neighborhood;
    private String municipality;
    private String city;
    private String state;
    private String postalCode;
    private String country;

    protected ProspectAddress() {}

    public ProspectAddress(String street, String exteriorNumber, String interiorNumber,
                           String neighborhood, String municipality, String city, String state,
                           String postalCode, String country) {
        this.street = street;
        this.exteriorNumber = exteriorNumber;
        this.interiorNumber = interiorNumber;
        this.neighborhood = neighborhood;
        this.municipality = municipality;
        this.city = city;
        this.state = state;
        this.postalCode = postalCode;
        this.country = (country != null) ? country : "MX";
    }

    public String getStreet()          { return street; }
    public String getExteriorNumber()  { return exteriorNumber; }
    public String getInteriorNumber()  { return interiorNumber; }
    public String getNeighborhood()    { return neighborhood; }
    public String getMunicipality()    { return municipality; }
    public String getCity()            { return city; }
    public String getState()           { return state; }
    public String getPostalCode()      { return postalCode; }
    public String getCountry()         { return country; }
}
