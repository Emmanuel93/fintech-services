package com.fintech.scoring.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "circulo_reports", schema = "scoring")
public class CirculoReport {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID reportId;

    @Column(nullable = false, updatable = false)
    private UUID prefetchId;

    @Column(nullable = false, updatable = false)
    private UUID prospectId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CirculoReportStatus status;

    private String folioConsulta;
    private String folioOtorgante;
    private String claveOtorgante;
    private String declaracionesConsumidor;

    @Column(nullable = false)
    private Instant queriedAt;

    private String errorCode;
    @Column(length = 500)
    private String errorMessage;

    private String personaNombres;
    private String personaApellidoPaterno;
    private String personaApellidoMaterno;
    private LocalDate personaFechaNacimiento;
    @Column(length = 13)
    private String personaRfc;
    @Column(length = 18)
    private String personaCurp;
    @Column(length = 11)
    private String personaNss;
    @Column(length = 1)
    private String personaSexo;
    @Column(length = 1)
    private String personaEstadoCivil;
    @Column(length = 2)
    private String personaNacionalidad;
    private Integer personaNumDependientes;

    private Integer ficoScoreValor;
    @Column(length = 500)
    private String ficoScoreRazones;

    @OneToMany(mappedBy = "report", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CirculoCredit> credits = new ArrayList<>();

    @OneToMany(mappedBy = "report", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CirculoAddress> addresses = new ArrayList<>();

    @OneToMany(mappedBy = "report", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CirculoEmployment> employments = new ArrayList<>();

    @OneToMany(mappedBy = "report", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CirculoInquiry> inquiries = new ArrayList<>();

    @OneToMany(mappedBy = "report", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CirculoScore> scores = new ArrayList<>();

    protected CirculoReport() {}

    public static Builder builder(UUID reportId, UUID prefetchId, UUID prospectId) {
        return new Builder(reportId, prefetchId, prospectId);
    }

    public void addCredit(CirculoCredit c)         { credits.add(c); }
    public void addAddress(CirculoAddress a)       { addresses.add(a); }
    public void addEmployment(CirculoEmployment e) { employments.add(e); }
    public void addInquiry(CirculoInquiry i)       { inquiries.add(i); }
    public void addScore(CirculoScore s)           { scores.add(s); }

    // ── Getters ──────────────────────────────────────────────────────────────
    public UUID getReportId()                      { return reportId; }
    public UUID getPrefetchId()                    { return prefetchId; }
    public UUID getProspectId()                    { return prospectId; }
    public CirculoReportStatus getStatus()         { return status; }
    public String getFolioConsulta()               { return folioConsulta; }
    public String getFolioOtorgante()              { return folioOtorgante; }
    public String getClaveOtorgante()              { return claveOtorgante; }
    public String getDeclaracionesConsumidor()     { return declaracionesConsumidor; }
    public Instant getQueriedAt()                  { return queriedAt; }
    public String getErrorCode()                   { return errorCode; }
    public String getErrorMessage()                { return errorMessage; }
    public String getPersonaNombres()              { return personaNombres; }
    public String getPersonaApellidoPaterno()      { return personaApellidoPaterno; }
    public String getPersonaApellidoMaterno()      { return personaApellidoMaterno; }
    public LocalDate getPersonaFechaNacimiento()   { return personaFechaNacimiento; }
    public String getPersonaRfc()                  { return personaRfc; }
    public String getPersonaCurp()                 { return personaCurp; }
    public String getPersonaNss()                  { return personaNss; }
    public String getPersonaSexo()                 { return personaSexo; }
    public String getPersonaEstadoCivil()          { return personaEstadoCivil; }
    public String getPersonaNacionalidad()         { return personaNacionalidad; }
    public Integer getPersonaNumDependientes()     { return personaNumDependientes; }
    public Integer getFicoScoreValor()             { return ficoScoreValor; }
    public String getFicoScoreRazones()            { return ficoScoreRazones; }
    public List<CirculoCredit> getCredits()        { return Collections.unmodifiableList(credits); }
    public List<CirculoAddress> getAddresses()     { return Collections.unmodifiableList(addresses); }
    public List<CirculoEmployment> getEmployments(){ return Collections.unmodifiableList(employments); }
    public List<CirculoInquiry> getInquiries()     { return Collections.unmodifiableList(inquiries); }
    public List<CirculoScore> getScores()          { return Collections.unmodifiableList(scores); }

    // ── Builder ──────────────────────────────────────────────────────────────
    public static class Builder {
        private final CirculoReport r;

        Builder(UUID reportId, UUID prefetchId, UUID prospectId) {
            r = new CirculoReport();
            r.reportId   = reportId;
            r.prefetchId = prefetchId;
            r.prospectId = prospectId;
            r.queriedAt  = Instant.now();
        }

        public Builder status(CirculoReportStatus v)            { r.status = v; return this; }
        public Builder folioConsulta(String v)                  { r.folioConsulta = v; return this; }
        public Builder folioOtorgante(String v)                 { r.folioOtorgante = v; return this; }
        public Builder claveOtorgante(String v)                 { r.claveOtorgante = v; return this; }
        public Builder declaracionesConsumidor(String v)        { r.declaracionesConsumidor = v; return this; }
        public Builder errorCode(String v)                      { r.errorCode = v; return this; }
        public Builder errorMessage(String v)                   { r.errorMessage = v; return this; }
        public Builder personaNombres(String v)                 { r.personaNombres = v; return this; }
        public Builder personaApellidoPaterno(String v)         { r.personaApellidoPaterno = v; return this; }
        public Builder personaApellidoMaterno(String v)         { r.personaApellidoMaterno = v; return this; }
        public Builder personaFechaNacimiento(LocalDate v)      { r.personaFechaNacimiento = v; return this; }
        public Builder personaRfc(String v)                     { r.personaRfc = v; return this; }
        public Builder personaCurp(String v)                    { r.personaCurp = v; return this; }
        public Builder personaNss(String v)                     { r.personaNss = v; return this; }
        public Builder personaSexo(String v)                    { r.personaSexo = v; return this; }
        public Builder personaEstadoCivil(String v)             { r.personaEstadoCivil = v; return this; }
        public Builder personaNacionalidad(String v)            { r.personaNacionalidad = v; return this; }
        public Builder personaNumDependientes(Integer v)        { r.personaNumDependientes = v; return this; }
        public Builder ficoScoreValor(Integer v)                { r.ficoScoreValor = v; return this; }
        public Builder ficoScoreRazones(String v)               { r.ficoScoreRazones = v; return this; }
        public CirculoReport build()                            { return r; }
    }
}
