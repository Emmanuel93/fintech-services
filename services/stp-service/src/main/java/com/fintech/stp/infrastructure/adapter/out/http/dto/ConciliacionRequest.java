package com.fintech.stp.infrastructure.adapter.out.http.dto;

/** Cuerpo de {@code POST V2/conciliacion}. La página va como String, igual que en el contrato. */
public record ConciliacionRequest(
        String empresa,
        String firma,
        String page,
        String tipoOrden,
        String fechaOperacion
) {}
