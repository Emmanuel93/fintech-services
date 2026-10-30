package com.fintech.banking.infrastructure.adapter.in.messaging;

import com.fintech.shared.testing.EventContract;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lo que banking lee de los hechos del carril del dinero, contra lo que publican sus productores.
 *
 * <p>Las réplicas llevan <b>los nombres del productor</b>: si el payload de aquí renombra un campo,
 * la prueba se pone roja. La contraparte —el payload real de STP contra una réplica de éste— vive
 * en stp-service ({@code OrderSettledContractTest}).
 */
class RailEventsContractTest {

    /** {@code stp.order-settled} tal como lo arma {@code KafkaStpEventPublisher}. */
    record LiquidadaComoLaPublicaStp(String eventId, UUID paymentRequestId, UUID companyId,
                                     String trackingKey, BigDecimal amount, LocalDate businessDate,
                                     String cepUrl, String cepBeneficiaryName,
                                     boolean beneficiaryNameMatches, Instant settledAt,
                                     String observedVia, Instant occurredOn) {}

    /** Lo que STP publicaba antes: sin importe, sin fecha y sin id. Así llegó durante meses. */
    record LiquidadaComoLaPublicabaStp(UUID paymentRequestId, UUID companyId, String trackingKey,
                                       String cepUrl, String cepBeneficiaryName,
                                       boolean beneficiaryNameMatches, Instant settledAt,
                                       String observedVia, Instant occurredOn) {}

    /** {@code disbursement.stp-requested} — {@code PaymentOrderRequestedPayload}, con las CLABEs. */
    record DespachoComoLoPublicaDisbursement(UUID paymentRequestId, UUID companyId, BigDecimal amount,
                                            String currency, String beneficiaryName,
                                            String beneficiaryAccount, String beneficiaryAccountType,
                                            String beneficiaryTaxId, Integer beneficiaryInstitution,
                                            String concept, Long numericReference, String paymentType,
                                            String correlationId, UUID orderingAccountId,
                                            String orderingClabe, String orderingHolderName,
                                            String orderingTaxId, String orderingClientRef,
                                            Instant occurredOn) {}

    /** {@code disbursement.accepted} — {@code DisbursementAcceptedPayload}. */
    record AceptadaComoLaPublicaDisbursement(UUID disbursementId, UUID companyId, String sourceSystem,
                                             String sourceType, String sourceReference,
                                             String sourceEventId, Map<String, String> sourceMetadata,
                                             BigDecimal amount, String currency, String provider,
                                             String externalRef, String correlationId,
                                             Instant occurredOn) {}

    private static final Instant LIQUIDADA = Instant.parse("2026-10-31T03:30:00Z");

    @Test
    @DisplayName("stp.order-settled llega con id, importe y fecha de operación: se puede proyectar el cargo")
    void liquidacionConStp() {
        var p = EventContract.between(new LiquidadaComoLaPublicaStp(
                        "e-1", UUID.randomUUID(), UUID.randomUUID(), "KRD2026103000000000000004",
                        new BigDecimal("45000.00"), LocalDate.of(2026, 10, 30), "https://cep", "ROSA",
                        true, LIQUIDADA, "POLL_ORDER", LIQUIDADA),
                        StpOrderSettledListener.StpOrderSettledPayload.class)
                .requires("eventId",          StpOrderSettledListener.StpOrderSettledPayload::eventId)
                .requires("paymentRequestId", StpOrderSettledListener.StpOrderSettledPayload::paymentRequestId)
                .requires("trackingKey",      StpOrderSettledListener.StpOrderSettledPayload::trackingKey)
                .requires("amount",           StpOrderSettledListener.StpOrderSettledPayload::amount)
                .requires("businessDate",     StpOrderSettledListener.StpOrderSettledPayload::businessDate)
                .requires("settledAt",        StpOrderSettledListener.StpOrderSettledPayload::settledAt)
                .verify();

        assertThat(p.amount()).isEqualByComparingTo("45000.00");
        assertThat(StpOrderSettledListener.eventIdOf(p)).isEqualTo("e-1");
        assertThat(StpOrderSettledListener.date(p)).isEqualTo(LocalDate.of(2026, 10, 30));
    }

    @Test
    @DisplayName("un emisor viejo sin id ni fecha se tolera: id estable por orden y día Banxico de la liquidación")
    void toleraAlEmisorViejo() {
        UUID orden = UUID.randomUUID();
        var p = EventContract.between(new LiquidadaComoLaPublicabaStp(orden, UUID.randomUUID(), "KRD-1",
                        "https://cep", "ROSA", true, LIQUIDADA, "POLL_ORDER", LIQUIDADA),
                        StpOrderSettledListener.StpOrderSettledPayload.class)
                .requires("paymentRequestId", StpOrderSettledListener.StpOrderSettledPayload::paymentRequestId)
                .requires("trackingKey",      StpOrderSettledListener.StpOrderSettledPayload::trackingKey)
                .verify();

        // Mismo id para la misma orden: una reentrega no duplica el movimiento proyectado.
        assertThat(StpOrderSettledListener.eventIdOf(p)).isEqualTo(StpOrderSettledListener.eventIdOf(p)).isNotBlank();
        // 03:30Z del 31 es 21:30 del 30 en la Ciudad de México: el día de operación es el 30.
        assertThat(StpOrderSettledListener.date(p)).isEqualTo(LocalDate.of(2026, 10, 30));
    }

    @Test
    @DisplayName("el despacho al conector trae el pago y la cuenta por la que sale")
    void despachoConDisbursement() {
        UUID cuenta = UUID.randomUUID();
        var p = EventContract.between(new DespachoComoLoPublicaDisbursement(
                        UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("45000.00"), "MXN",
                        "ROSA ELENA IBARRA SALGADO", "646180157000000004", "40", "IASR900612B0B", 646,
                        "DISPOSICION DE CREDITO", null, null, "corr", cuenta, "646180000000000012",
                        "FINTECH", "FSE000101AAA", "C1", Instant.now()),
                        PayoutTraceListener.PaymentRequestedPayload.class)
                .requires("paymentRequestId",  PayoutTraceListener.PaymentRequestedPayload::paymentRequestId)
                .requires("amount",            PayoutTraceListener.PaymentRequestedPayload::amount)
                .requires("orderingAccountId", PayoutTraceListener.PaymentRequestedPayload::orderingAccountId)
                .verify();

        assertThat(p.orderingAccountId()).isEqualTo(cuenta);
    }

    @Test
    @DisplayName("la aceptación trae de qué crédito viene el pago y su clave de rastreo")
    void aceptacionConDisbursement() {
        UUID credito = UUID.randomUUID();
        var p = EventContract.between(new AceptadaComoLaPublicaDisbursement(
                        UUID.randomUUID(), UUID.randomUUID(), "credit-portfolio", "DISPOSITION", "disp-1",
                        "disp-1", Map.of("creditAccountId", credito.toString(), "dispositionId", "disp-1"),
                        new BigDecimal("45000.00"), "MXN", "STP", "KRD-1", credito.toString(), Instant.now()),
                        PayoutTraceListener.DisbursementAcceptedPayload.class)
                .requires("disbursementId", PayoutTraceListener.DisbursementAcceptedPayload::disbursementId)
                .requires("sourceSystem",   PayoutTraceListener.DisbursementAcceptedPayload::sourceSystem)
                .requires("sourceMetadata", PayoutTraceListener.DisbursementAcceptedPayload::sourceMetadata)
                .requires("externalRef",    PayoutTraceListener.DisbursementAcceptedPayload::externalRef)
                .verify();

        assertThat(PayoutTraceListener.creditAccountOf(p)).isEqualTo(credito);
    }
}
