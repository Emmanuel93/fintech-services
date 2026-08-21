package com.fintech.origination.domain.event;

import com.fintech.origination.domain.ChannelType;
import com.fintech.origination.domain.ProspectDocument;
import com.fintech.origination.domain.ProspectType;
import com.fintech.shared.event.DomainEvent;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Emitted after a new prospect is successfully registered.
 *
 * Consumers:
 *  - identity-service   → provisions user credentials
 *  - party-service      → creates PartyDraft, stores KYC documents and Círculo consent
 *  - scoring-service    → triggers Círculo/Buró PREFETCH only (no evaluation) when
 *                         circuloConsentAccepted=true. The scoring evaluation is deferred
 *                         to ScoreRequested (emitted when a product is selected via
 *                         CreditApplication). See ADR-001 — persona ≠ crédito.
 *  - notifications      → sends welcome/confirmation message
 *  - audit              → compliance logging (LFPDPPP)
 *
 * Note: this event no longer carries productTypeIntent — the product is chosen later
 * in a CreditApplication, not at person onboarding.
 */
public class ProspectCreatedEvent extends DomainEvent {

    private final UUID prospectId;
    private final ProspectType prospectType;
    private final String firstName;
    private final String lastName1;
    private final String lastName2;
    private final String curp;
    private final String rfc;
    private final LocalDate dateOfBirth;
    private final String phone;
    private final String email;

    // Address snapshot — required by scoring to call Círculo de Crédito
    private final String street;
    private final String exteriorNumber;
    private final String interiorNumber;
    private final String neighborhood;
    private final String municipality;
    private final String city;
    private final String state;
    private final String postalCode;
    private final String country;

    private final ChannelType channelType;
    private final boolean circuloConsentAccepted;
    private final Instant circuloConsentAcceptedAt;
    private final List<ProspectDocument> documents;
    private final Instant registeredAt;
    private final String username;
    /** Raw password — identity-service is responsible for BCrypt hashing on provisioning. */
    private final String password;

    public ProspectCreatedEvent(
            UUID prospectId,
            ProspectType prospectType,
            String firstName,
            String lastName1,
            String lastName2,
            String curp,
            String rfc,
            LocalDate dateOfBirth,
            String phone,
            String email,
            String street,
            String exteriorNumber,
            String interiorNumber,
            String neighborhood,
            String municipality,
            String city,
            String state,
            String postalCode,
            String country,
            ChannelType channelType,
            boolean circuloConsentAccepted,
            Instant circuloConsentAcceptedAt,
            List<ProspectDocument> documents,
            Instant registeredAt,
            String username,
            String password,
            String correlationId) {
        super(correlationId);
        this.prospectId               = prospectId;
        this.prospectType             = prospectType;
        this.firstName                = firstName;
        this.lastName1                = lastName1;
        this.lastName2                = lastName2;
        this.curp                     = curp;
        this.rfc                      = rfc;
        this.dateOfBirth              = dateOfBirth;
        this.phone                    = phone;
        this.email                    = email;
        this.street                   = street;
        this.exteriorNumber           = exteriorNumber;
        this.interiorNumber           = interiorNumber;
        this.neighborhood             = neighborhood;
        this.municipality             = municipality;
        this.city                     = city;
        this.state                    = state;
        this.postalCode               = postalCode;
        this.country                  = country;
        this.channelType              = channelType;
        this.circuloConsentAccepted   = circuloConsentAccepted;
        this.circuloConsentAcceptedAt = circuloConsentAcceptedAt;
        this.documents                = List.copyOf(documents);
        this.registeredAt             = registeredAt;
        this.username                 = username;
        this.password                 = password;
    }

    public UUID getProspectId()                       { return prospectId; }
    public ProspectType getProspectType()             { return prospectType; }
    public String getFirstName()                      { return firstName; }
    public String getLastName1()                      { return lastName1; }
    public String getLastName2()                      { return lastName2; }
    public String getCurp()                           { return curp; }
    public String getRfc()                            { return rfc; }
    public LocalDate getDateOfBirth()                 { return dateOfBirth; }
    public String getPhone()                          { return phone; }
    public String getEmail()                          { return email; }
    public String getStreet()                         { return street; }
    public String getExteriorNumber()                 { return exteriorNumber; }
    public String getInteriorNumber()                 { return interiorNumber; }
    public String getNeighborhood()                   { return neighborhood; }
    public String getMunicipality()                   { return municipality; }
    public String getCity()                           { return city; }
    public String getState()                          { return state; }
    public String getPostalCode()                     { return postalCode; }
    public String getCountry()                        { return country; }
    public ChannelType getChannelType()               { return channelType; }
    public boolean isCirculoConsentAccepted()         { return circuloConsentAccepted; }
    public Instant getCirculoConsentAcceptedAt()      { return circuloConsentAcceptedAt; }
    public List<ProspectDocument> getDocuments()      { return documents; }
    public Instant getRegisteredAt()                  { return registeredAt; }
    public String getUsername()                       { return username; }
    public String getPassword()                       { return password; }
}
