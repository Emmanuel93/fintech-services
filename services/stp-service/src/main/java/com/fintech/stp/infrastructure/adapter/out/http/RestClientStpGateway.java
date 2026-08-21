package com.fintech.stp.infrastructure.adapter.out.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.stp.application.StpProperties;
import com.fintech.stp.application.port.out.StpGatewayPort;
import com.fintech.stp.infrastructure.adapter.out.http.dto.ConciliacionRequest;
import com.fintech.stp.infrastructure.adapter.out.http.dto.ConciliacionResponse;
import com.fintech.stp.infrastructure.adapter.out.http.dto.ConciliacionResponse.Detalle;
import com.fintech.stp.infrastructure.adapter.out.http.dto.RegistraOrdenPagoRequest;
import com.fintech.stp.infrastructure.adapter.out.http.dto.RegistraOrdenPagoResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Cliente HTTP real de STP. Único punto del monorepo que abre una conexión a internet (EG-01).
 *
 * <p>{@code RestClient} de Spring 6.1 con timeouts por request. El legado usaba Unirest 1.4.9
 * —abandonada desde 2017— y configuraba los timeouts con {@code Unirest.setTimeouts(...)}, un
 * método estático global mutado desde código concurrente.
 */
@Component
@ConditionalOnProperty(name = "fintech.stp.gateway.mode", havingValue = "real", matchIfMissing = true)
public class RestClientStpGateway implements StpGatewayPort {

    private static final Logger log = LoggerFactory.getLogger(RestClientStpGateway.class);
    private static final DateTimeFormatter STP_DATE = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT);

    private static final String PATH_REGISTRA = "ordenPago/registra";
    private static final String PATH_CONCILIACION = "V2/conciliacion";

    private final RestClient dispersionClient;
    private final RestClient consultaClient;
    private final ObjectMapper objectMapper;

    public RestClientStpGateway(@Qualifier("stpDispersionClient") RestClient dispersionClient,
                                 @Qualifier("stpConsultaClient") RestClient consultaClient,
                                 ObjectMapper objectMapper,
                                 StpProperties properties) {
        this.dispersionClient = dispersionClient;
        this.consultaClient = consultaClient;
        this.objectMapper = objectMapper;
        log.info("STP gateway in REAL mode — base={} consulta={}",
                properties.getBaseUrl(), properties.getConsultaBaseUrl());
    }

    @Override
    public RegistrationResult registerPaymentOrder(PaymentOrderRequest request) {
        RegistraOrdenPagoRequest body = new RegistraOrdenPagoRequest(
                request.claveRastreo(), request.conceptoPago(), request.cuentaBeneficiario(),
                request.cuentaOrdenante(), request.empresa(),
                Integer.valueOf(request.fechaOperacion().format(STP_DATE)),
                request.firma(), request.institucionContraparte(), request.institucionOperante(),
                request.monto(), request.nombreBeneficiario(), request.nombreOrdenante(),
                request.referenciaNumerica(), request.rfcCurpBeneficiario(), request.rfcCurpOrdenante(),
                request.tipoCuentaBeneficiario(), request.tipoCuentaOrdenante(), request.tipoPago());

        RegistraOrdenPagoResponse response = dispersionClient.put()
                .uri(PATH_REGISTRA)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(RegistraOrdenPagoResponse.class);

        if (response == null || response.resultado() == null || response.resultado().id() == null) {
            throw new IllegalStateException("STP devolvió una respuesta sin 'resultado.id'");
        }
        return new RegistrationResult(response.resultado().id(), response.resultado().descripcionError());
    }

    @Override
    public ReconciliationPage queryReconciliation(String stpEmpresa, String tipoOrden, LocalDate businessDate,
                                                   int page, String signature) {
        ConciliacionRequest body = new ConciliacionRequest(
                stpEmpresa, signature, String.valueOf(page), tipoOrden, businessDate.format(STP_DATE));

        ConciliacionResponse response = consultaClient.post()
                .uri(PATH_CONCILIACION)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(ConciliacionResponse.class);

        if (response == null) {
            throw new IllegalStateException("STP devolvió una conciliación vacía");
        }
        List<Detalle> datos = response.datos() != null ? response.datos() : List.of();
        return new ReconciliationPage(
                response.estado(), response.mensaje(),
                response.total() != null ? response.total() : datos.size(),
                datos.stream().map(this::toEntry).toList());
    }

    private ReconciliationEntry toEntry(Detalle detalle) {
        return new ReconciliationEntry(
                detalle.claveRastreo(), detalle.estado(),
                detalle.causaDevolucion() != null ? String.valueOf(detalle.causaDevolucion()) : null,
                detalle.urlCEP(), detalle.nombreCep(), detalle.rfcCep(), detalle.sello(),
                detalle.tsLiquidacion(), detalle.tsCaptura(), detalle.monto(),
                detalle.cuentaBeneficiario(), serialize(detalle), serializeWithoutSello(detalle));
    }

    /** El payload crudo es evidencia regulatoria (SO-01). Se archiva tal cual llegó. */
    private String serialize(Detalle detalle) {
        try {
            return objectMapper.writeValueAsString(detalle);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    /**
     * Lo mismo sin el campo {@code sello}.
     *
     * <p><strong>Pendiente de confirmar contra la especificación de STP:</strong> lo más probable es
     * que STP firme una cadena original posicional, no este JSON. Hasta tener el documento, la
     * política de verificación por defecto es {@code WARN} — se registra la discrepancia y se aplica
     * el cambio de estado, en vez de bloquear todas las liquidaciones contra una suposición.
     * Ver {@code fintech.stp.settlement.signature-policy}.
     */
    private String serializeWithoutSello(Detalle detalle) {
        try {
            var node = objectMapper.convertValue(detalle, java.util.Map.class);
            node.remove("sello");
            return objectMapper.writeValueAsString(node);
        } catch (RuntimeException | JsonProcessingException e) {
            return "{}";
        }
    }
}
