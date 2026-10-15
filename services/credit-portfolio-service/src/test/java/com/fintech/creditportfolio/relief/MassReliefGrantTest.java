package com.fintech.creditportfolio.relief;

import com.fintech.creditportfolio.application.port.in.ManageReliefProgramUseCase.Proponer;
import com.fintech.creditportfolio.application.port.out.CreditAccountRepository;
import com.fintech.creditportfolio.application.port.out.CreditPortfolioEventPublisher;
import com.fintech.creditportfolio.application.port.out.InstallmentRepository;
import com.fintech.creditportfolio.application.port.out.ReliefProgramRepository;
import com.fintech.creditportfolio.application.service.relief.ReliefProgramService;
import com.fintech.creditportfolio.domain.CreditAccount;
import com.fintech.creditportfolio.domain.CreditAccountStatus;
import com.fintech.creditportfolio.domain.Installment;
import com.fintech.creditportfolio.domain.event.ReliefGrantedEvent;
import com.fintech.creditportfolio.domain.relief.ReliefEnrollment;
import com.fintech.creditportfolio.domain.relief.ReliefProgram;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Otorgar el programa: correr los vencimientos de todo el padrón.
 *
 * <p>Comparte el mecanismo del salto de pago —correr el vencimiento— y se diferencia en tres cosas:
 * lo otorga la institución, aplica masivamente por criterio, y <b>marca forborne</b>.
 */
@ExtendWith(MockitoExtension.class)
class MassReliefGrantTest {

    @Mock CreditAccountRepository accounts;
    @Mock InstallmentRepository installments;
    @Mock CreditPortfolioEventPublisher eventPublisher;

    ReliefProgramService service;

    private final Map<UUID, ReliefProgram> saved = new HashMap<>();
    private final List<ReliefEnrollment> enrollments = new ArrayList<>();
    private final Map<UUID, List<Installment>> schedules = new HashMap<>();

    /**
     * El primer vencimiento del plan, <b>relativo a hoy</b> y no una fecha literal.
     *
     * <h2>Por qué, que es lo que importa</h2>
     *
     * Era {@code LocalDate.of(2026, 6, 15)}, y con eso la prueba «el apoyo deja el DPD en CERO»
     * pasaba por <b>un día</b> de margen: el apoyo corre tres períodos, así que el primer
     * vencimiento quedaba en el 15 de septiembre de 2026 — y la prueba se escribió el 14. Al día
     * siguiente empezó a fallar, no porque nada cambiara en el código sino porque <b>pasó el tiempo</b>.
     *
     * <p>El DPD lo recalcula producción contra {@code LocalDate.now()}, y eso está bien: es un dato
     * del calendario real. Lo que estaba mal era afirmar un resultado dependiente de hoy sobre un
     * calendario fijo en el pasado. Anclado a hoy, el escenario —una cuenta con vencimientos
     * atrasados a la que el apoyo los corre hasta el futuro— se sostiene el día que se corra.
     *
     * <p>Queda a dos meses atrás: las primeras cuotas nacen vencidas (que es el escenario) y al
     * correrlas tres períodos todas caen en el futuro con un mes de margen.
     */
    private static final LocalDate FIRST_DUE_DATE = LocalDate.now().minusMonths(2).withDayOfMonth(15);

    /** Repositorio en memoria: lo que se prueba es la regla, no el mapeo. */
    private final ReliefProgramRepository programs = new ReliefProgramRepository() {
        @Override public ReliefProgram save(ReliefProgram p) { saved.put(p.getId(), p); return p; }
        @Override public Optional<ReliefProgram> findById(UUID id) {
            return Optional.ofNullable(saved.get(id));
        }
        @Override public List<ReliefProgram> findAll() { return List.copyOf(saved.values()); }
        @Override public ReliefEnrollment saveEnrollment(ReliefEnrollment e) {
            enrollments.add(e); return e;
        }
        @Override public List<ReliefEnrollment> findEnrollmentsByProgram(UUID id) {
            return enrollments.stream().filter(e -> e.getReliefProgramId().equals(id)).toList();
        }
        @Override public List<ReliefEnrollment> findActiveEnrollmentsByAccount(UUID account) {
            return enrollments.stream()
                    .filter(e -> e.getCreditAccountId().equals(account) && e.isActive()).toList();
        }
        @Override public boolean existsEnrollment(UUID programId, UUID account) {
            return enrollments.stream().anyMatch(e ->
                    e.getReliefProgramId().equals(programId) && e.getCreditAccountId().equals(account));
        }
    };

    @BeforeEach
    void init() {
        service = new ReliefProgramService(programs, accounts, installments, eventPublisher);
        saved.clear(); enrollments.clear(); schedules.clear();
        lenient().when(installments.findByScheduleIdOrdered(any()))
                .thenAnswer(inv -> schedules.getOrDefault(inv.getArgument(0), List.of()));
    }

    private CreditAccount account(String producto, int dpd) {
        CreditAccount c = CreditAccount.fromSnapshot(
                UUID.randomUUID(), "CTR-" + UUID.randomUUID(), UUID.randomUUID(),
                "PL-001", 1, producto, "INSTALLMENT",
                new BigDecimal("12000"), null, 6,
                new BigDecimal("0.24"), new BigDecimal("0.36"),
                "FRENCH", new BigDecimal("0.16"), "032180000118359719", "BAJO", null, null);
        c.activate(new BigDecimal("12000"));
        c.updateDelinquency(dpd);

        List<Installment> plan = new ArrayList<>();
        for (int n = 1; n <= 6; n++) {
            plan.add(Installment.of(c.getCreditAccountId(), n, FIRST_DUE_DATE.plusMonths(n - 1L),
                    new BigDecimal("2000"), new BigDecimal("200")));
        }
        schedules.put(c.getCreditAccountId(), plan);
        return c;
    }

    private ReliefProgram authorized(Integer dpdMaximo) {
        ReliefProgram p = service.propose(new Proponer("Apoyo huracán", "NATURAL_DISASTER", 3,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 8, 31),
                null, null, null, dpdMaximo, LocalDate.of(2026, 5, 31), "ACCRUES"), "ana.riesgos");
        return service.authorize(p.getId(), "luis.direccion");
    }

    @Test
    @DisplayName("otorgar corre TRES períodos a todas las cuotas pendientes")
    void itshiftsThreePeriods() {
        CreditAccount c = account("PERSONAL_LOAN", 0);
        lenient().when(accounts.findAllByStatus(CreditAccountStatus.ACTIVE)).thenReturn(List.of(c));
        ReliefProgram p = authorized(null);

        assertThat(service.grant(p.getId())).isEqualTo(1);

        List<Installment> plan = schedules.get(c.getCreditAccountId());
        assertThat(plan.get(0).getDueDate()).isEqualTo(FIRST_DUE_DATE.plusMonths(3));
        assertThat(plan.get(0).getOriginalDueDate()).isEqualTo(FIRST_DUE_DATE);
        // TODAS se corren, no sólo las tres primeras: dejar las de atrás quietas amontonaría los
        // vencimientos justo cuando la vigencia termina.
        assertThat(plan.get(5).getDueDate()).isEqualTo(FIRST_DUE_DATE.plusMonths(5).plusMonths(3));
    }

    @Test
    @DisplayName("un programa NO autorizado no se puede otorgar")
    void sinAutorizarNoSeOtorga() {
        ReliefProgram p = service.propose(new Proponer("Apoyo", "SANITARY", 3,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 8, 31),
                null, null, null, null, LocalDate.of(2026, 5, 31), "ACCRUES"), "ana.riesgos");

        assertThatThrownBy(() -> service.grant(p.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sólo se otorga uno APPROVED");
    }

    @Test
    @DisplayName("reejecutar el otorgamiento NO corre los vencimientos otra vez")
    void esIdempotente() {
        CreditAccount c = account("PERSONAL_LOAN", 0);
        lenient().when(accounts.findAllByStatus(CreditAccountStatus.ACTIVE)).thenReturn(List.of(c));
        ReliefProgram p = authorized(null);

        service.grant(p.getId());
        LocalDate afterTheFirst = schedules.get(c.getCreditAccountId()).get(0).getDueDate();

        // Es lo que pasa cuando alguien reintenta un lote que pareció fallar.
        assertThat(service.grant(p.getId())).isZero();
        assertThat(schedules.get(c.getCreditAccountId()).get(0).getDueDate())
                .isEqualTo(afterTheFirst);
    }

    @Test
    @DisplayName("el padrón se puede simular ANTES de autorizar, sin tocar nada")
    void elPadronSeSimula() {
        CreditAccount alCorriente = account("PERSONAL_LOAN", 0);
        CreditAccount muyAtrasada = account("PERSONAL_LOAN", 200);
        lenient().when(accounts.findAllByStatus(CreditAccountStatus.ACTIVE))
                .thenReturn(List.of(alCorriente, muyAtrasada));

        ReliefProgram p = service.propose(new Proponer("Apoyo", "SECURITY", 3,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 8, 31),
                null, null, null, 30, LocalDate.of(2026, 5, 31), "ACCRUES"), "ana.riesgos");

        var roster = service.simulateRoster(p.getId());

        assertThat(roster.eligibleAccounts()).isEqualTo(1);
        // Quien firma ve a cuántas cuentas alcanza antes de que se mueva un solo vencimiento.
        assertThat(schedules.get(alCorriente.getCreditAccountId()).get(0).getDueDate())
                .isEqualTo(FIRST_DUE_DATE);
    }

    @Test
    @DisplayName("el otorgamiento publica el hecho por cuenta — es lo que marca forborne")
    void publicaPorCuenta() {
        CreditAccount a = account("PERSONAL_LOAN", 0);
        CreditAccount b = account("PERSONAL_LOAN", 10);
        lenient().when(accounts.findAllByStatus(CreditAccountStatus.ACTIVE)).thenReturn(List.of(a, b));
        ReliefProgram p = authorized(null);

        service.grant(p.getId());

        ArgumentCaptor<ReliefGrantedEvent> captor = ArgumentCaptor.forClass(ReliefGrantedEvent.class);
        verify(eventPublisher, times(2)).publishReliefGranted(captor.capture());

        ReliefGrantedEvent e = captor.getAllValues().get(0);
        assertThat(e.deferredPeriods()).isEqualTo(3);
        assertThat(e.reason()).isEqualTo("NATURAL_DISASTER");
        assertThat(e.firstDueBefore()).isEqualTo(FIRST_DUE_DATE);
        assertThat(e.firstDueAfter()).isEqualTo(FIRST_DUE_DATE.plusMonths(3));
    }

    @Test
    @DisplayName("el expediente guarda de qué fecha a qué fecha se movió y con cuánto atraso entró")
    void elExpediente() {
        CreditAccount c = account("PERSONAL_LOAN", 12);
        lenient().when(accounts.findAllByStatus(CreditAccountStatus.ACTIVE)).thenReturn(List.of(c));
        ReliefProgram p = authorized(null);

        service.grant(p.getId());

        // Sin esto, «se te movió el pago» no tiene respaldo ante el cliente ni ante una revisión.
        ReliefEnrollment e = enrollments.get(0);
        assertThat(e.getDaysDelinquentAtEnrollment()).isEqualTo(12);
        assertThat(e.getInstallmentsMoved()).isEqualTo(6);
        assertThat(e.getFirstDueBefore()).isEqualTo(FIRST_DUE_DATE);
        assertThat(e.getFirstDueAfter()).isEqualTo(FIRST_DUE_DATE.plusMonths(3));
        assertThat(e.isActive()).isTrue();
    }

    @Test
    @DisplayName("el apoyo deja el DPD en CERO en el acto — no espera al job nocturno")
    void thedpdDropsImmediately() {
        // Una cuenta con 200 días de atraso a la que se le otorga apoyo.
        CreditAccount c = account("PERSONAL_LOAN", 200);
        lenient().when(accounts.findAllByStatus(CreditAccountStatus.ACTIVE)).thenReturn(List.of(c));
        ReliefProgram p = authorized(null);

        service.grant(p.getId());

        // Con los vencimientos corridos no queda cuota vencida: el apoyo surte efecto por el
        // mecanismo de siempre —DPD cero cierra el caso de cobranza y apaga la mora (BK-20)— sin
        // inventar un segundo camino. Y se publica en el acto: entre el otorgamiento y la
        // medianoche, cobranza seguiría escalando a alguien a quien se le acaba de dar un respiro.
        assertThat(c.getDaysDelinquent()).isZero();
        verify(eventPublisher).publishDelinquencyStatusUpdated(
                org.mockito.ArgumentMatchers.argThat(e -> e.getDaysDelinquent() == 0));
    }

    @Test
    @DisplayName("una cuota ya PAGADA no se mueve")
    void paidInstallmentsDoNotMove() {
        CreditAccount c = account("PERSONAL_LOAN", 0);
        schedules.get(c.getCreditAccountId()).get(0).applyPayment(new BigDecimal("999999"));
        lenient().when(accounts.findAllByStatus(CreditAccountStatus.ACTIVE)).thenReturn(List.of(c));
        ReliefProgram p = authorized(null);

        service.grant(p.getId());

        // Mover una cuota ya pagada no apoya a nadie y ensucia el expediente.
        assertThat(schedules.get(c.getCreditAccountId()).get(0).getDueDate()).isEqualTo(FIRST_DUE_DATE);
        assertThat(enrollments.get(0).getInstallmentsMoved()).isEqualTo(5);
    }
}
