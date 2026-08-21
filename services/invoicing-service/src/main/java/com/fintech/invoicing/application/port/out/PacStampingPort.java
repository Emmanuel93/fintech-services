package com.fintech.invoicing.application.port.out;

import com.fintech.invoicing.domain.Invoice;

import java.util.UUID;

/**
 * Timbrado ante el PAC (Proveedor Autorizado de Certificación). Stub por ahora
 * ({@code NoopPacAdapter}) — el adaptador real se integra después sin tocar el dominio.
 */
public interface PacStampingPort {
    StampResult stamp(Invoice invoice);

    record StampResult(UUID folioFiscal, String serie, long folio) {}
}
