package com.fintech.scoring.domain;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "circulo_addresses", schema = "scoring")
public class CirculoAddress {

    @Id private UUID addressId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "report_id", nullable = false)
    private CirculoReport report;

    private String direccion;
    private String coloniaPoblacion;
    private String delegacionMunicipio;
    private String ciudad;
    @Column(length = 4) private String estado;
    @Column(length = 5) private String cp;
    @Column(length = 1) private String tipoDomicilio;
    private LocalDate fechaResidencia;
    private LocalDate fechaRegistro;
    private String idDomicilio;

    protected CirculoAddress() {}

    public CirculoAddress(UUID addressId, CirculoReport report,
                          String direccion, String coloniaPoblacion, String delegacionMunicipio,
                          String ciudad, String estado, String cp, String tipoDomicilio,
                          LocalDate fechaResidencia, LocalDate fechaRegistro, String idDomicilio) {
        this.addressId = addressId;
        this.report = report;
        this.direccion = direccion;
        this.coloniaPoblacion = coloniaPoblacion;
        this.delegacionMunicipio = delegacionMunicipio;
        this.ciudad = ciudad;
        this.estado = estado;
        this.cp = cp;
        this.tipoDomicilio = tipoDomicilio;
        this.fechaResidencia = fechaResidencia;
        this.fechaRegistro = fechaRegistro;
        this.idDomicilio = idDomicilio;
    }

    public UUID getAddressId()  { return addressId; }
    public String getDireccion(){ return direccion; }
    public String getCiudad()   { return ciudad; }
}
