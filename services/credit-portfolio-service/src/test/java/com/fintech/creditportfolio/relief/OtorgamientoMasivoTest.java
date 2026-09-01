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
class OtorgamientoMasivoTest {

    @Mock CreditAccountRepository accounts;
    @Mock InstallmentRepository installments;
    @Mock CreditPortfolioEventPublisher eventPublisher;

    ReliefProgramService servicio;

    private final Map<UUID, ReliefProgram> guardados = new HashMap<>();
    private final List<ReliefEnrollment> inscripciones = new ArrayList<>();
    private final Map<UUID, List<Installment>> calendarios = new HashMap<>();

    private static final LocalDate PRIMERA = LocalDate.of(2026, 6, 15);

    /** Repositorio en memoria: lo que se prueba es la regla, no el mapeo. */
    private final ReliefProgramRepository programas = new ReliefProgramRepository() {
        @Override public ReliefProgram save(ReliefProgram p) { guardados.put(p.getId(), p); return p; }
        @Override public Optional<ReliefProgram> findById(UUID id) {
            return Optional.ofNullable(guardados.get(id));
        }
        @Override public List<ReliefProgram> findAll() { return List.copyOf(guardados.values()); }
        @Override public ReliefEnrollment saveEnrollment(ReliefEnrollment e) {
            inscripciones.add(e); return e;
        }
        @Override public List<ReliefEnrollment> findEnrollmentsByProgram(UUID id) {
            return inscripciones.stream().filter(e -> e.getReliefProgramId().equals(id)).toList();
        }
        @Override public List<ReliefEnrollment> findActiveEnrollmentsByAccount(UUID cuenta) {
            return inscripciones.stream()
                    .filter(e -> e.getCreditAccountId().equals(cuenta) && e.estaVigente()).toList();
        }
        @Override public boolean existsEnrollment(UUID programId, UUID cuenta) {
            return inscripciones.stream().anyMatch(e ->
                    e.getReliefProgramId().equals(programId) && e.getCreditAccountId().equals(cuenta));
        }
    };

    @BeforeEach
    void init() {
        servicio = new ReliefProgramService(programas, accounts, installments, eventPublisher);
        guardados.clear(); inscripciones.clear(); calendarios.clear();
        lenient().when(installments.findByScheduleIdOrdered(any()))
                .thenAnswer(inv -> calendarios.getOrDefault(inv.getArgument(0), List.of()));
    }

    private CreditAccount cuenta(String producto, int dpd) {
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
            plan.add(Installment.of(c.getCreditAccountId(), n, PRIMERA.plusMonths(n - 1L),
                    new BigDecimal("2000"), new BigDecimal("200")));
        }
        calendarios.put(c.getCreditAccountId(), plan);
        return c;
    }

    private ReliefProgram autorizado(Integer dpdMaximo) {
        ReliefProgram p = servicio.proponer(new Proponer("Apoyo huracán", "NATURAL_DISASTER", 3,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 8, 31),
                null, null, null, dpdMaximo, LocalDate.of(2026, 5, 31), "ACCRUES"), "ana.riesgos");
        return servicio.autorizar(p.getId(), "luis.direccion");
    }

    @Test
    @DisplayName("otorgar corre TRES períodos a todas las cuotas pendientes")
    void correTresPeriodos() {
        CreditAccount c = cuenta("PERSONAL_LOAN", 0);
        lenient().when(accounts.findAllByStatus(CreditAccountStatus.ACTIVE)).thenReturn(List.of(c));
        ReliefProgram p = autorizado(null);

        assertThat(servicio.otorgar(p.getId())).isEqualTo(1);

        List<Installment> plan = calendarios.get(c.getCreditAccountId());
        assertThat(plan.get(0).getDueDate()).isEqualTo(PRIMERA.plusMonths(3));
        assertThat(plan.get(0).getOriginalDueDate()).isEqualTo(PRIMERA);
        // TODAS se corren, no sólo las tres primeras: dejar las de atrás quietas amontonaría los
        // vencimientos justo cuando la vigencia termina.
        assertThat(plan.get(5).getDueDate()).isEqualTo(PRIMERA.plusMonths(5).plusMonths(3));
    }

    @Test
    @DisplayName("un programa NO autorizado no se puede otorgar")
    void sinAutorizarNoSeOtorga() {
        ReliefProgram p = servicio.proponer(new Proponer("Apoyo", "SANITARY", 3,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 8, 31),
                null, null, null, null, LocalDate.of(2026, 5, 31), "ACCRUES"), "ana.riesgos");

        assertThatThrownBy(() -> servicio.otorgar(p.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sólo se otorga uno APPROVED");
    }

    @Test
    @DisplayName("reejecutar el otorgamiento NO corre los vencimientos otra vez")
    void esIdempotente() {
        CreditAccount c = cuenta("PERSONAL_LOAN", 0);
        lenient().when(accounts.findAllByStatus(CreditAccountStatus.ACTIVE)).thenReturn(List.of(c));
        ReliefProgram p = autorizado(null);

        servicio.otorgar(p.getId());
        LocalDate trasElPrimero = calendarios.get(c.getCreditAccountId()).get(0).getDueDate();

        // Es lo que pasa cuando alguien reintenta un lote que pareció fallar.
        assertThat(servicio.otorgar(p.getId())).isZero();
        assertThat(calendarios.get(c.getCreditAccountId()).get(0).getDueDate())
                .isEqualTo(trasElPrimero);
    }

    @Test
    @DisplayName("el padrón se puede simular ANTES de autorizar, sin tocar nada")
    void elPadronSeSimula() {
        CreditAccount alCorriente = cuenta("PERSONAL_LOAN", 0);
        CreditAccount muyAtrasada = cuenta("PERSONAL_LOAN", 200);
        lenient().when(accounts.findAllByStatus(CreditAccountStatus.ACTIVE))
                .thenReturn(List.of(alCorriente, muyAtrasada));

        ReliefProgram p = servicio.proponer(new Proponer("Apoyo", "SECURITY", 3,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 8, 31),
                null, null, null, 30, LocalDate.of(2026, 5, 31), "ACCRUES"), "ana.riesgos");

        var padron = servicio.simularPadron(p.getId());

        assertThat(padron.cuentasElegibles()).isEqualTo(1);
        // Quien firma ve a cuántas cuentas alcanza antes de que se mueva un solo vencimiento.
        assertThat(calendarios.get(alCorriente.getCreditAccountId()).get(0).getDueDate())
                .isEqualTo(PRIMERA);
    }

    @Test
    @DisplayName("el otorgamiento publica el hecho por cuenta — es lo que marca forborne")
    void publicaPorCuenta() {
        CreditAccount a = cuenta("PERSONAL_LOAN", 0);
        CreditAccount b = cuenta("PERSONAL_LOAN", 10);
        lenient().when(accounts.findAllByStatus(CreditAccountStatus.ACTIVE)).thenReturn(List.of(a, b));
        ReliefProgram p = autorizado(null);

        servicio.otorgar(p.getId());

        ArgumentCaptor<ReliefGrantedEvent> captor = ArgumentCaptor.forClass(ReliefGrantedEvent.class);
        verify(eventPublisher, times(2)).publishReliefGranted(captor.capture());

        ReliefGrantedEvent e = captor.getAllValues().get(0);
        assertThat(e.deferredPeriods()).isEqualTo(3);
        assertThat(e.reason()).isEqualTo("NATURAL_DISASTER");
        assertThat(e.firstDueBefore()).isEqualTo(PRIMERA);
        assertThat(e.firstDueAfter()).isEqualTo(PRIMERA.plusMonths(3));
    }

    @Test
    @DisplayName("el expediente guarda de qué fecha a qué fecha se movió y con cuánto atraso entró")
    void elExpediente() {
        CreditAccount c = cuenta("PERSONAL_LOAN", 12);
        lenient().when(accounts.findAllByStatus(CreditAccountStatus.ACTIVE)).thenReturn(List.of(c));
        ReliefProgram p = autorizado(null);

        servicio.otorgar(p.getId());

        // Sin esto, «se te movió el pago» no tiene respaldo ante el cliente ni ante una revisión.
        ReliefEnrollment e = inscripciones.get(0);
        assertThat(e.getDaysDelinquentAtEnrollment()).isEqualTo(12);
        assertThat(e.getInstallmentsMoved()).isEqualTo(6);
        assertThat(e.getFirstDueBefore()).isEqualTo(PRIMERA);
        assertThat(e.getFirstDueAfter()).isEqualTo(PRIMERA.plusMonths(3));
        assertThat(e.estaVigente()).isTrue();
    }

    @Test
    @DisplayName("el apoyo deja el DPD en CERO en el acto — no espera al job nocturno")
    void elDpdCaeEnElActo() {
        // Una cuenta con 200 días de atraso a la que se le otorga apoyo.
        CreditAccount c = cuenta("PERSONAL_LOAN", 200);
        lenient().when(accounts.findAllByStatus(CreditAccountStatus.ACTIVE)).thenReturn(List.of(c));
        ReliefProgram p = autorizado(null);

        servicio.otorgar(p.getId());

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
    void lasPagadasNoSeMueven() {
        CreditAccount c = cuenta("PERSONAL_LOAN", 0);
        calendarios.get(c.getCreditAccountId()).get(0).applyPayment(new BigDecimal("999999"));
        lenient().when(accounts.findAllByStatus(CreditAccountStatus.ACTIVE)).thenReturn(List.of(c));
        ReliefProgram p = autorizado(null);

        servicio.otorgar(p.getId());

        // Mover una cuota ya pagada no apoya a nadie y ensucia el expediente.
        assertThat(calendarios.get(c.getCreditAccountId()).get(0).getDueDate()).isEqualTo(PRIMERA);
        assertThat(inscripciones.get(0).getInstallmentsMoved()).isEqualTo(5);
    }
}
