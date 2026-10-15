package com.fintech.creditportfolio.relief;

import com.fintech.creditportfolio.application.port.out.CreditAccountRepository;
import com.fintech.creditportfolio.application.port.out.CreditPortfolioEventPublisher;
import com.fintech.creditportfolio.application.port.out.InstallmentRepository;
import com.fintech.creditportfolio.application.port.out.ReliefProgramRepository;
import com.fintech.creditportfolio.application.service.relief.ReliefProgramService;
import com.fintech.creditportfolio.domain.CreditAccount;
import com.fintech.creditportfolio.domain.CreditAccountStatus;
import com.fintech.creditportfolio.domain.relief.ReliefEnrollment;
import com.fintech.creditportfolio.domain.relief.ReliefProgram;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Un crédito no puede estar bajo dos apoyos a la vez.
 *
 * <p>La guarda que existía era <b>por programa</b>: impedía reotorgar el mismo lote dos veces. No
 * impedía lo que pasó de verdad — dos programas <em>distintos</em>, cada uno autorizado a diferir
 * tres períodos, alcanzando la misma cartera. Cuarenta y dos cuentas recibieron los dos y se les
 * corrió el vencimiento <b>seis meses</b>: el doble de lo que nadie autorizó, sin más rastro que
 * dos filas de inscripción.
 *
 * <p>Ninguno de los dos programas era incorrecto por separado. El invariante no es de programa sino
 * de cuenta, y ésa es la clase de defecto que sólo aparece cuando dos configuraciones válidas
 * tienen que convivir.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReliefProgramsDoNotStackTest {

    @Mock ReliefProgramRepository programs;
    @Mock CreditAccountRepository accounts;
    @Mock InstallmentRepository installments;
    @Mock CreditPortfolioEventPublisher eventPublisher;

    private ReliefProgramService servicio;
    private ReliefProgram program;
    private CreditAccount account;

    @BeforeEach
    void preparar() {
        servicio = new ReliefProgramService(programs, accounts, installments, eventPublisher);

        program = ReliefProgram.propose("Apoyo por contingencia", "NATURAL_DISASTER", 3,
                LocalDate.now(), LocalDate.now().plusMonths(3), null, null, null, null,
                LocalDate.now().minusDays(1), "ACCRUES", "ana.riesgos");
        program.authorize("luis.direccion");

        account = CreditAccount.fromSnapshot(
                UUID.randomUUID(), "CTR-1", UUID.randomUUID(),
                "PL-IND-STD-V1", 1, "PERSONAL_LOAN", "INSTALLMENT",
                new java.math.BigDecimal("20000"), null, 12,
                new java.math.BigDecimal("24.0"), new java.math.BigDecimal("36.0"),
                "FRENCH", new java.math.BigDecimal("1.0"), "032180000118359719", "BAJO", null, null);
        account.activate(new java.math.BigDecimal("20000"));

        given(programs.findById(any())).willReturn(Optional.of(program));
        given(accounts.findAllByStatus(CreditAccountStatus.ACTIVE)).willReturn(List.of(account));
        given(installments.findByScheduleIdOrdered(any())).willReturn(List.of());
        given(programs.existsEnrollment(any(), any())).willReturn(false);
    }

    @Test
    @DisplayName("Una cuenta ya apoyada por otro programa NO entra al segundo")
    void asupportedAccountDoesNotEnterAnotherProgram() {
        given(programs.findActiveEnrollmentsByAccount(account.getCreditAccountId()))
                .willReturn(List.of(ReliefEnrollment.from(UUID.randomUUID(),
                        account.getCreditAccountId(), 0, 3, LocalDate.now(), LocalDate.now().plusMonths(3))));

        int inscritas = servicio.grant(program.getId());

        assertThat(inscritas).isZero();
        verify(programs, never()).saveEnrollment(any());
        verify(installments, never()).saveAll(any());
    }

    @Test
    @DisplayName("Sin apoyo previo, la cuenta sí entra — la guarda no bloquea el caso normal")
    void sin_apoyo_previo_si_entra() {
        given(programs.findActiveEnrollmentsByAccount(account.getCreditAccountId()))
                .willReturn(List.of());

        assertThat(servicio.grant(program.getId())).isEqualTo(1);
        verify(programs).saveEnrollment(any());
    }

    @Test
    @DisplayName("El padrón declara cuántas quedan fuera por venir de otro apoyo")
    void el_padron_declara_las_ya_apoyadas() {
        given(programs.findActiveEnrollmentsByAccount(account.getCreditAccountId()))
                .willReturn(List.of(ReliefEnrollment.from(UUID.randomUUID(),
                        account.getCreditAccountId(), 0, 3, LocalDate.now(), LocalDate.now().plusMonths(3))));

        var roster = servicio.simulateRoster(program.getId());

        // Quien firma tiene que ver las dos cifras. Con sólo "0 elegibles" no se distingue
        // "el criterio no alcanza a nadie" de "toda esa cartera ya viene de otro programa".
        assertThat(roster.eligibleAccounts()).isZero();
        assertThat(roster.alreadySupported()).isEqualTo(1);
    }
}
