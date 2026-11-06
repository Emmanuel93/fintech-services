package com.fintech.creditportfolio.application.service.relief;

import com.fintech.creditportfolio.domain.config.BillingCycle;
import com.fintech.creditportfolio.domain.config.SemiMonthlyCalendar;
import com.fintech.creditportfolio.application.port.in.ManageReliefProgramUseCase;
import com.fintech.creditportfolio.application.port.out.CreditAccountRepository;
import com.fintech.creditportfolio.application.port.out.CreditPortfolioEventPublisher;
import com.fintech.creditportfolio.application.port.out.InstallmentRepository;
import com.fintech.creditportfolio.application.port.out.ReliefProgramRepository;
import com.fintech.creditportfolio.application.service.PlanDeferral;
import com.fintech.creditportfolio.application.service.ProductConfigResolver;
import com.fintech.creditportfolio.domain.CreditAccount;
import com.fintech.creditportfolio.domain.CreditAccountStatus;
import com.fintech.creditportfolio.domain.Installment;
import com.fintech.creditportfolio.domain.InstallmentStatus;
import com.fintech.creditportfolio.domain.config.ProductConfigVersion;
import com.fintech.creditportfolio.domain.event.DelinquencyStatusUpdatedEvent;
import com.fintech.creditportfolio.domain.event.ReliefGrantedEvent;
import com.fintech.creditportfolio.domain.relief.ReliefEnrollment;
import com.fintech.creditportfolio.domain.relief.ReliefProgram;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import com.fintech.shared.time.BusinessClock;

/**
 * Otorga un programa de apoyo: corre N vencimientos a cada cuenta del padrón.
 *
 * <p><b>Comparte el mecanismo del salto de pago</b> —correr el vencimiento— y se diferencia en tres
 * cosas: lo otorga la institución y no el cliente, aplica masivamente por criterio de elegibilidad,
 * y <b>marca forborne</b>. Se implementa sobre la misma base para no tener dos formas de mover una
 * fecha de pago.
 */
@Service
public class ReliefProgramService implements ManageReliefProgramUseCase {

    private static final Logger log = LoggerFactory.getLogger(ReliefProgramService.class);
    /** Tamaño de la muestra que devuelve el padrón simulado; no es un tope del otorgamiento. */
    private static final int MUESTRA = 20;

    private final ReliefProgramRepository programs;
    private final CreditAccountRepository accounts;
    private final InstallmentRepository installments;
    private final CreditPortfolioEventPublisher eventPublisher;
    private final ProductConfigResolver configResolver;
    private final PlanDeferral deferral;

    public ReliefProgramService(ReliefProgramRepository programs,
                                CreditAccountRepository accounts,
                                InstallmentRepository installments,
                                CreditPortfolioEventPublisher eventPublisher,
                                ProductConfigResolver configResolver,
                                PlanDeferral deferral) {
        this.programs      = programs;
        this.accounts       = accounts;
        this.installments   = installments;
        this.eventPublisher = eventPublisher;
        this.configResolver = configResolver;
        this.deferral       = deferral;
    }

    @Override
    @Transactional
    public ReliefProgram propose(Proponer alta, String proposedBy) {
        ReliefProgram p = ReliefProgram.propose(alta.name(), alta.reason(), alta.deferredPeriods(),
                alta.validFrom(), alta.validTo(), alta.productCode(), alta.originUnitCode(),
                alta.region(), alta.maxDaysDelinquent(), alta.eligibilityCutoffDate(),
                alta.accrualDuringRelief(), proposedBy);
        log.info("Programa de apoyo PROPUESTO id={} nombre='{}' motivo={} períodos={} por={}",
                p.getId(), p.getName(), p.getReason(), p.getDeferredPeriods(), proposedBy);
        return programs.save(p);
    }

    @Override
    @Transactional
    public ReliefProgram authorize(UUID programId, String approvedBy) {
        ReliefProgram p = require(programId);
        p.authorize(approvedBy);
        log.warn("Programa de apoyo AUTORIZADO id={} nombre='{}' por={} — la reserva de la cartera "
                        + "alcanzada SUBIRÁ por el paso a STAGE_2",
                p.getId(), p.getName(), approvedBy);
        return programs.save(p);
    }

    @Override
    @Transactional(readOnly = true)
    public EligibilityRoster simulateRoster(UUID programId) {
        ReliefProgram p = require(programId);
        List<CreditAccount> reached = reachedBy(p);
        List<UUID> eligible = reached.stream()
                .filter(c -> !isAlreadyUnderRelief(c.getCreditAccountId()))
                .map(CreditAccount::getCreditAccountId)
                .toList();
        return new EligibilityRoster(programId, eligible.size(), reached.size() - eligible.size(),
                eligible.stream().limit(MUESTRA).toList());
    }

    @Override
    @Transactional
    public int grant(UUID programId) {
        ReliefProgram p = require(programId);
        if (!p.isAuthorized()) {
            throw new IllegalStateException("El programa " + programId + " está en " + p.getStatus()
                    + ": sólo se otorga uno APPROVED");
        }

        int inscritas = 0;
        int alreadySupported = 0;
        for (CreditAccount account : reachedBy(p)) {
            // Reejecutar el otorgamiento —que es lo que pasa cuando alguien reintenta un lote que
            // pareció fallar— no debe correr los vencimientos otra vez.
            if (programs.existsEnrollment(programId, account.getCreditAccountId())) {
                continue;
            }
            // Y tampoco si viene de OTRO programa vigente: dos apoyos no se suman.
            if (isAlreadyUnderRelief(account.getCreditAccountId())) {
                alreadySupported++;
                continue;
            }
            enroll(p, account);
            inscritas++;
        }

        // WARN y no INFO, y con las dos cifras: otorgar un apoyo masivo mueve los vencimientos de
        // un segmento entero. Que haya cartera excluida por venir de otro programa es justo lo que
        // quien revisa necesita ver sin ir a buscarlo.
        log.warn("Programa de apoyo OTORGADO id={} nombre='{}' cuentas={} yaApoyadas={} períodos={} devengo={}",
                programId, p.getName(), inscritas, alreadySupported, p.getDeferredPeriods(),
                p.getAccrualDuringRelief());
        return inscritas;
    }

    private void enroll(ReliefProgram p, CreditAccount reached) {
        // The program reached the account through a listing read earlier: it is locked and read again before its
        // delinquency is recalculated and saved, so no balance applied since is put back.
        CreditAccount account = accounts.lockAndLoad(reached.getCreditAccountId()).orElse(reached);
        List<Installment> calendario = installments
                .findByScheduleIdOrdered(account.getCreditAccountId()).stream()
                .filter(i -> i.getStatus() != InstallmentStatus.PAID)
                .sorted(Comparator.comparing(Installment::getDueDate))
                .toList();

        LocalDate antes = calendario.isEmpty() ? null : calendario.get(0).getDueDate();

        // Se corren TODAS las pendientes, no sólo las primeras N: dejar las de atrás quietas
        // amontonaría los vencimientos justo cuando la vigencia termina.
        // A period is the product's: two periods of a weekly microloan are two weeks, not two months. Every plan used
        // to move by months whatever its frequency (the cadence was "MONTHLY" on both branches of a ternary).
        // Each one moves from its plan's date and lands on a business day, as a skipped payment does.
        deferral.defer(calendario, cadenceOf(account), p.getDeferredPeriods(), fortnightOf(account));
        installments.saveAll(calendario);

        LocalDate despues = calendario.isEmpty() ? null : calendario.get(0).getDueDate();

        int dpdAlEntrar = account.getDaysDelinquent();
        programs.saveEnrollment(ReliefEnrollment.from(p.getId(), account.getCreditAccountId(),
                dpdAlEntrar, calendario.size(), antes, despues));

        // **El apoyo surte efecto por el mecanismo que ya existe, no por uno nuevo.** Con los
        // vencimientos corridos no queda ninguna cuota vencida, así que el DPD es cero — y un DPD
        // en cero cierra el caso de cobranza y apaga la mora (BK-20) por los caminos de siempre.
        //
        // Se recalcula y publica AQUÍ en vez de esperar al job nocturno: entre el otorgamiento y la
        // medianoche, cobranza seguiría escalando a alguien a quien se le acaba de dar un respiro.
        recalculateDpd(account, calendario);

        // BK-35 · el otorgamiento marca forborne. Riesgo aplica el piso STAGE_2 y reinicia el reloj
        // de cura, igual que con cualquier reestructura. Es el costo asumido del apoyo.
        eventPublisher.publishReliefGranted(new ReliefGrantedEvent(
                p.getId(), p.getName(), p.getReason(), account.getCreditAccountId(),
                account.getObligorPartyId(), p.getDeferredPeriods(), p.getValidTo(),
                p.waivesAccrual(), antes, despues));
    }

    /** The fortnight's cutoff days of the account's product: where a distributor's installments move to. */
    private SemiMonthlyCalendar fortnightOf(CreditAccount account) {
        return BillingCycle.semiMonthlyOf(configResolver.resolveForAccount(account.getProductCode(), account.getProductVersion())
                .map(ProductConfigVersion::getBillingCycle).orElse(null));
    }

    /** The account's payment frequency, from its product version; monthly when the product does not say. */
    private String cadenceOf(CreditAccount account) {
        return configResolver.resolveForAccount(account.getProductCode(), account.getProductVersion())
                .map(ProductConfigVersion::getPaymentFrequency)
                .filter(f -> f != null && !f.isBlank())
                .orElse("MONTHLY");
    }

    /**
     * Recalcula el atraso con el calendario ya corrido y lo publica.
     *
     * <p>Es el mismo cálculo del envejecido nocturno —días desde la cuota vencida más antigua sin
     * pagar— aplicado en el momento, para que el efecto del apoyo no dependa de a qué hora se
     * otorgó.
     */
    private void recalculateDpd(CreditAccount account, List<Installment> calendario) {
        LocalDate hoy = BusinessClock.current().today();
        LocalDate masAntigua = calendario.stream()
                .map(Installment::getDueDate)
                .filter(d -> d.isBefore(hoy))
                .min(Comparator.naturalOrder())
                .orElse(null);

        int dias = masAntigua == null ? 0 : (int) ChronoUnit.DAYS.between(masAntigua, hoy);
        BigDecimal overduePrincipal = masAntigua == null ? BigDecimal.ZERO
                : calendario.stream()
                        .filter(i -> i.getDueDate().isBefore(hoy))
                        .map(Installment::getPrincipalAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

        account.updateDelinquency(dias);
        accounts.save(account);

        eventPublisher.publishDelinquencyStatusUpdated(new DelinquencyStatusUpdatedEvent(
                account.getCreditAccountId(), account.getObligorPartyId(), account.getContractNumber(),
                dias, overduePrincipal, masAntigua));
    }

    /**
     * El padrón.
     *
     * <p>El DPD se evalúa contra el <b>estado actual</b> de la cuenta; la fecha de corte del programa
     * es lo que hace que ese número sea el de antes del anuncio y no el de después. Sin ella, un
     * programa anunciado hoy alcanzaría a quien dejó de pagar al enterarse de que venía.
     */
    /** Las que cumplen el criterio del programa, sin mirar todavía si ya vienen de otro. */
    private List<CreditAccount> reachedBy(ReliefProgram p) {
        // Sólo cuentas ACTIVE: una liquidada o quebrantada no tiene vencimientos que correr, y
        // meterla al padrón inflaría la cifra que alguien mira para autorizar.
        return accounts.findAllByStatus(CreditAccountStatus.ACTIVE).stream()
                .filter(c -> p.reaches(c.getProductType(), c.getOriginUnitCode(), null,
                        c.getDaysDelinquent()))
                .toList();
    }

    /**
     * Una cuenta bajo apoyo vigente no entra a otro programa.
     *
     * <p>La guarda que ya existía era <b>por programa</b>: impedía reotorgar el mismo lote dos
     * veces. No impedía lo otro, que es lo que pasó de verdad: dos programas distintos, cada uno
     * autorizado a diferir tres períodos, alcanzando la misma cartera. Cuarenta y dos cuentas
     * recibieron los dos y se les corrió el vencimiento <b>seis meses</b> — el doble de lo que
     * nadie autorizó, y sin ningún registro de que hubiera pasado más allá de dos filas.
     *
     * <p>Cada programa se veía razonable por separado. El invariante no es de programa sino de
     * cuenta: <b>un crédito no puede estar bajo dos apoyos a la vez</b>. Si hace falta extender el
     * apoyo, se otorga un programa con más períodos, no uno encima de otro.
     */
    private boolean isAlreadyUnderRelief(UUID creditAccountId) {
        return !programs.findActiveEnrollmentsByAccount(creditAccountId).isEmpty();
    }

    private ReliefProgram require(UUID id) {
        return programs.findById(id).orElseThrow(
                () -> new NoSuchElementException("Sin programa de apoyo " + id));
    }
}
