package com.fintech.creditportfolio.contrato;

import com.fintech.creditportfolio.domain.event.BalanceUpdatedEvent;
import com.fintech.creditportfolio.domain.event.CreditAccountActivatedEvent;
import com.fintech.creditportfolio.domain.event.DelinquencyStatusUpdatedEvent;
import com.fintech.creditportfolio.domain.event.DispositionAuthorizedEvent;
import com.fintech.shared.testing.ContratoDeEvento;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El contrato entre cartera y quienes la escuchan.
 *
 * <p><b>Es la prueba que faltaba.</b> Cada consumidor re-declara a mano el payload de cartera en su
 * propio paquete ACL, así que hasta ahora <b>un cambio de contrato no rompía nada</b>: ni al
 * compilar ni al probar. Los tres listeners muertos del monorepo y los cuarenta y dos topics sin
 * consumidor sobrevivieron por eso.
 *
 * <p>Aquí se serializa el evento real de cartera y se deserializa con una réplica de lo que el
 * consumidor declara. Si el productor deja de mandar un campo que el consumidor lee, esto falla.
 */
class ContratoCarteraTest {

    // ── Réplicas de lo que declaran los consumidores reales ──────────────────
    // Se copian a propósito, con los mismos nombres de campo: si divergen del original, la prueba
    // deja de proteger. Es el precio de no acoplar los módulos entre sí.

    record ActivadaComoLaVeCharges(
            String eventId, UUID creditAccountId, UUID obligorPartyId, String productType,
            String productBehavior, BigDecimal nominalRate, BigDecimal moratoriumRate,
            BigDecimal principalBalance, Instant activatedAt,
            /** BNPL: sin este campo, charges devenga desde el día uno (BK-28). */
            LocalDate accrualStartDate) {}

    record ActivadaComoLaVeElCierre(
            UUID creditAccountId, UUID obligorPartyId, String productType, String productBehavior,
            String originUnitCode, Instant activatedAt,
            String paymentFrequency, Integer termPeriods, BigDecimal nominalRate) {}

    record SaldoComoLoVeContabilidad(
            UUID creditAccountId, UUID obligorPartyId, BigDecimal principalBalance,
            BigDecimal accruedInterestBalance, BigDecimal penaltyBalance, BigDecimal totalDebt,
            String triggerEvent, String accountStatus, long balanceVersion, String originUnitCode,
            Instant occurredOn, BigDecimal eventAmount,
            BigDecimal principalDelta, BigDecimal interestDelta, BigDecimal penaltyDelta) {}

    /**
     * Lo que {@code charges} declara de {@code delinquency-status-updated}.
     *
     * <p>Los dos campos que importan son {@code overduePrincipal} y {@code oldestDueDate}: son la
     * base del moratorio y la fecha desde la que corre. Sin ellos, charges tendría que adivinar la
     * base — y adivinarla es exactamente lo que hacía cuando usaba el saldo completo del crédito y
     * cobraba once veces lo que correspondía (BK-19).
     */
    record MoraComoLaVeCharges(
            UUID creditAccountId, UUID obligorPartyId, String contractNumber,
            int daysDelinquent, BigDecimal overduePrincipal, LocalDate oldestDueDate) {}

    @Test
    @DisplayName("charges recibe la BASE del moratorio, no sólo los días de atraso")
    void contratoDeLaMoraConCharges() {
        var evento = new DelinquencyStatusUpdatedEvent(
                UUID.randomUUID(), UUID.randomUUID(), "CTR-202603-ABCD1234", 27,
                new BigDecimal("1800.00"), LocalDate.of(2026, 5, 10));

        var p = ContratoDeEvento.entre(evento, MoraComoLaVeCharges.class)
                .exige("creditAccountId",  MoraComoLaVeCharges::creditAccountId)
                .exige("overduePrincipal", MoraComoLaVeCharges::overduePrincipal)
                .exige("oldestDueDate",    MoraComoLaVeCharges::oldestDueDate)
                .verificar();

        assertThat(p.overduePrincipal()).isEqualByComparingTo("1800.00");
        assertThat(p.oldestDueDate()).isEqualTo(LocalDate.of(2026, 5, 10));
        assertThat(p.daysDelinquent()).isEqualTo(27);
    }

    @Test
    @DisplayName("una cuenta curada viaja con base CERO — es lo que apaga la mora del otro lado")
    void laCuraTambienViaja() {
        // Sin este caso, apagar la mora dependería de que charges dedujera la cura de los días en
        // cero. `overduePrincipal = 0` lo dice explícitamente, y `oldestDueDate` nulo lo confirma.
        var curada = new DelinquencyStatusUpdatedEvent(
                UUID.randomUUID(), UUID.randomUUID(), "CTR-202603-ABCD1234", 0,
                BigDecimal.ZERO, null);

        var p = ContratoDeEvento.entre(curada, MoraComoLaVeCharges.class)
                .exige("overduePrincipal", MoraComoLaVeCharges::overduePrincipal)
                .verificar();

        assertThat(p.overduePrincipal()).isEqualByComparingTo("0");
        assertThat(p.oldestDueDate()).isNull();
    }

    @Test
    @DisplayName("BNPL: la fecha de arranque del devengo LLEGA a charges")
    void contratoDeBnplConCharges() {
        // Es exactamente el defecto que esta misma clase cazó con `paymentFrequency`: un campo sin
        // getter no se serializa, compila igual y no rompe ninguna otra prueba. Aquí el síntoma
        // sería que un crédito «compra ahora, paga después» devenga interés desde el primer día.
        LocalDate arranque = LocalDate.of(2026, 7, 15);
        var conBnpl = new CreditAccountActivatedEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "PERSONAL_LOAN", "INSTALLMENT",
                new BigDecimal("0.32"), new BigDecimal("0.48"), BigDecimal.ZERO,
                new BigDecimal("20000.00"), null, "A", Instant.parse("2026-06-15T10:00:00Z"),
                "PROM-1", "SUC-001", null, "MONTHLY", 12, arranque);

        var p = ContratoDeEvento.entre(conBnpl, ActivadaComoLaVeCharges.class)
                .exige("accrualStartDate", ActivadaComoLaVeCharges::accrualStartDate)
                .verificar();

        assertThat(p.accrualStartDate()).isEqualTo(arranque);
    }

    /**
     * Lo que {@code disbursement} declara de {@code disposition-authorized} — <b>plano</b>, como el
     * resto de sus payloads y como lo espera el conector aguas abajo.
     */
    record AutorizadaComoLaVeDisbursement(
            UUID dispositionId, UUID creditAccountId, UUID companyId, String sourceCompanyKey,
            BigDecimal amount, String currency, String beneficiaryName, String beneficiaryAccount,
            String beneficiaryAccountType, String beneficiaryTaxId, String concept) {}

    @Test
    @DisplayName("disbursement recibe A QUIÉN pagarle — sin eso, la orden no se puede crear")
    void contratoDeLaDisposicionAutorizada() {
        var autorizada = new DispositionAuthorizedEvent(
                UUID.randomUUID(), UUID.randomUUID(), null, "SUC-001",
                new BigDecimal("20000.00"), "MXN",
                "JUAN PEREZ", "646180157000000004", "40", "PEPJ800101ABC",
                "DISPOSICION DE CREDITO");

        ContratoDeEvento.entre(autorizada, AutorizadaComoLaVeDisbursement.class)
                .exige("dispositionId",      AutorizadaComoLaVeDisbursement::dispositionId)
                .exige("amount",             AutorizadaComoLaVeDisbursement::amount)
                // Los tres que deciden adónde va el dinero. Si no llegan, disbursement crea una
                // orden sin beneficiario — o no la crea y la disposición se queda PROCESSING para
                // siempre, sin que nadie sepa por qué.
                .exige("beneficiaryName",    AutorizadaComoLaVeDisbursement::beneficiaryName)
                .exige("beneficiaryAccount", AutorizadaComoLaVeDisbursement::beneficiaryAccount)
                .exige("beneficiaryAccountType", AutorizadaComoLaVeDisbursement::beneficiaryAccountType)
                // Sin la clave de empresa, DB-07 rechaza la orden con UNRESOLVED_COMPANY antes de
                // llegar al proveedor: el pago nunca sale y nadie se entera.
                .exige("sourceCompanyKey",   AutorizadaComoLaVeDisbursement::sourceCompanyKey)
                .verificar();
    }

    private CreditAccountActivatedEvent activacion() {
        return new CreditAccountActivatedEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "PERSONAL_LOAN", "INSTALLMENT",
                new BigDecimal("0.32"), new BigDecimal("0.48"), BigDecimal.ZERO,
                new BigDecimal("20000.00"), null, "A", Instant.parse("2026-03-10T10:00:00Z"),
                "PROM-1", "SUC-001", null, "MONTHLY", 12);
    }

    @Test
    @DisplayName("charges recibe lo que necesita para crear su calendario de devengo")
    void contratoConCharges() {
        ContratoDeEvento.entre(activacion(), ActivadaComoLaVeCharges.class)
                .exige("creditAccountId", ActivadaComoLaVeCharges::creditAccountId)
                .exige("obligorPartyId",  ActivadaComoLaVeCharges::obligorPartyId)
                .exige("productType",     ActivadaComoLaVeCharges::productType)
                .exige("nominalRate",     ActivadaComoLaVeCharges::nominalRate)
                .exige("moratoriumRate",  ActivadaComoLaVeCharges::moratoriumRate)
                .exige("principalBalance", ActivadaComoLaVeCharges::principalBalance)
                .verificar();
    }

    @Test
    @DisplayName("el cierre recibe la cadencia y el plazo — sin ellos no puede derivar el corte")
    void contratoConElCierre() {
        // Son los dos campos que se añadieron al evento en TK-06 justo para esto. Si alguien los
        // quita, el calendario de corte deja de poder derivarse y esta prueba lo dice.
        var p = ContratoDeEvento.entre(activacion(), ActivadaComoLaVeElCierre.class)
                .exige("paymentFrequency", ActivadaComoLaVeElCierre::paymentFrequency)
                .exige("termPeriods",      ActivadaComoLaVeElCierre::termPeriods)
                .exige("activatedAt",      ActivadaComoLaVeElCierre::activatedAt)
                .exige("productBehavior",  ActivadaComoLaVeElCierre::productBehavior)
                .verificar();

        assertThat(p.paymentFrequency()).isEqualTo("MONTHLY");
        assertThat(p.termPeriods()).isEqualTo(12);
    }

    @Test
    @DisplayName("contabilidad recibe el importe y el desglose, que no debe deducir")
    void contratoConContabilidad() {
        // GL-12 y GL-13: el importe y el desglose se RECIBEN. Deducirlos restando saldos se rompe
        // con eventos fuera de orden — de diecisiete pagos reales sólo se asentaron cinco.
        BalanceUpdatedEvent evento = new BalanceUpdatedEvent(
                UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("18000"), new BigDecimal("533.33"), BigDecimal.ZERO,
                null, new BigDecimal("18533.33"), "PAYMENT_APPLIED", "ACTIVE", 7L, "SUC-001",
                Instant.parse("2026-04-10T23:00:00Z"), new BigDecimal("2000.00"),
                new BigDecimal("-1466.67"), new BigDecimal("-533.33"), BigDecimal.ZERO);

        var p = ContratoDeEvento.entre(evento, SaldoComoLoVeContabilidad.class)
                .exige("triggerEvent",   SaldoComoLoVeContabilidad::triggerEvent)
                .exige("accountStatus",  SaldoComoLoVeContabilidad::accountStatus)
                .exige("originUnitCode", SaldoComoLoVeContabilidad::originUnitCode)
                .exige("occurredOn",     SaldoComoLoVeContabilidad::occurredOn)
                .exige("eventAmount",    SaldoComoLoVeContabilidad::eventAmount)
                .exige("principalDelta", SaldoComoLoVeContabilidad::principalDelta)
                .exige("interestDelta",  SaldoComoLoVeContabilidad::interestDelta)
                .verificar();

        assertThat(p.balanceVersion()).isEqualTo(7L);
        assertThat(p.eventAmount()).isEqualByComparingTo("2000.00");
    }

    @Test
    @DisplayName("occurredOn viaja: es de donde contabilidad deriva el período del hecho")
    void laFechaDelHechoViaja() {
        // Sin ella, un devengo del 31 procesado a las 00:03 caería en el mes siguiente.
        BalanceUpdatedEvent evento = new BalanceUpdatedEvent(
                UUID.randomUUID(), UUID.randomUUID(), BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO,
                null, BigDecimal.TEN, "CHARGE_ORDINARY_INTEREST", "ACTIVE", 1L, "SUC-001",
                Instant.parse("2026-03-31T23:00:00Z"), BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ZERO, BigDecimal.ZERO);

        var p = ContratoDeEvento.entre(evento, SaldoComoLoVeContabilidad.class)
                .exige("occurredOn", SaldoComoLoVeContabilidad::occurredOn)
                .verificar();

        assertThat(p.occurredOn()).isEqualTo(Instant.parse("2026-03-31T23:00:00Z"));
    }
}
