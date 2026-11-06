package com.fintech.stp.domain.collection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What is answered to STP while it waits, rule by rule and in order. */
class CreditAcceptancePolicyTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final String CLABE = CollectionClabe.compose("646", "180", "3780", 7).value();
    private final CreditAcceptancePolicy policy = CreditAcceptancePolicy.standard(Set.of(1, 5, 19, 20, 21, 22));

    private static InboundCreditFacts facts(BigDecimal amount, Integer paymentType, String clabe, String trackingKey) {
        return new InboundCreditFacts(COMPANY, 1L, trackingKey, LocalDate.of(2026, 12, 1),
                Instant.parse("2026-12-01T01:00:00Z"), amount, clabe, null, "PAGADOR", "012180001234567891", 12,
                null, paymentType, "PAGO", 1234567L, null);
    }

    private static Optional<CollectionAccount> account(CollectionAccountStatus status) {
        CollectionAccount a = CollectionAccount.issue(COMPANY, UUID.randomUUID(), CollectionClabe.compose("646", "180", "3780", 7),
                null, null);
        if (status == CollectionAccountStatus.BLOCKED) a.block("prueba");
        if (status == CollectionAccountStatus.CLOSED) a.close("prueba");
        return Optional.of(a);
    }

    @Test
    @DisplayName("un abono completo a una CLABE activa se confirma")
    void confirmsAValidCredit() {
        assertThat(policy.evaluate(facts(new BigDecimal("5000.00"), 1, CLABE, "BANCO1"), account(CollectionAccountStatus.ACTIVE)))
                .isEmpty();
    }

    @Test
    @DisplayName("CLABE desconocida → causa 1; bloqueada → 2; cerrada → 3")
    void accountStates() {
        InboundCreditFacts f = facts(new BigDecimal("100.00"), 1, CLABE, "BANCO1");
        assertThat(policy.evaluate(f, Optional.empty())).contains(ReturnCause.CUENTA_INEXISTENTE);
        assertThat(policy.evaluate(f, account(CollectionAccountStatus.BLOCKED))).contains(ReturnCause.CUENTA_BLOQUEADA);
        assertThat(policy.evaluate(f, account(CollectionAccountStatus.CLOSED))).contains(ReturnCause.CUENTA_CANCELADA);
    }

    @Test
    @DisplayName("tipo de pago no permitido → causa 15; sin importe o sin clave → causa 14, antes que todo lo demás")
    void dataAndPaymentType() {
        assertThat(policy.evaluate(facts(new BigDecimal("100.00"), 7, CLABE, "BANCO1"), account(CollectionAccountStatus.ACTIVE)))
                .contains(ReturnCause.TIPO_DE_PAGO_ERRONEO);
        assertThat(policy.evaluate(facts(BigDecimal.ZERO, 1, CLABE, "BANCO1"), Optional.empty()))
                .as("falta de datos gana a la CLABE desconocida").contains(ReturnCause.FALTA_INFORMACION);
        assertThat(policy.evaluate(facts(new BigDecimal("100.00"), 1, CLABE, " "), account(CollectionAccountStatus.ACTIVE)))
                .contains(ReturnCause.FALTA_INFORMACION);
    }

    @Test
    @DisplayName("una CLABE cerrada no se reabre")
    void closedNeverReopens() {
        CollectionAccount a = account(CollectionAccountStatus.CLOSED).orElseThrow();
        assertThatThrownBy(() -> a.reactivate("intento"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("no se reabre");
    }
}
