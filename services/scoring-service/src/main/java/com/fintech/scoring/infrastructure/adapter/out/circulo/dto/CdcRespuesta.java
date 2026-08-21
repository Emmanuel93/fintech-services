package com.fintech.scoring.infrastructure.adapter.out.circulo.dto;

import java.util.List;

public record CdcRespuesta(
        String folioConsulta,
        String folioConsultaOtorgante,
        String claveOtorgante,
        String declaracionesConsumidor,
        CdcPersonaRespuesta persona,
        List<CdcConsulta> consultas,
        List<CdcCredito> creditos,
        List<CdcDomicilioRespuesta> domicilios,
        List<CdcEmpleo> empleos,
        List<CdcScore> scores,
        List<CdcMensaje> mensajes
) {}
