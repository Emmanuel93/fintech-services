package com.fintech.beneficiary.infrastructure.adapter.out.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.fintech.shared.time.BusinessClock;

/**
 * La cartera del distribuidor: su línea y el avance de cada disposición.
 *
 * <p>Este servicio no lleva contabilidad de la línea. Cuánto tiene autorizado, cuánto le queda y
 * cuánto debe lo sabe credit-portfolio, y preguntárselo en cada consulta es más barato que
 * mantener una copia que puede quedar desfasada justo cuando importa —al decidir si cabe una
 * colocación más.
 */
@Component
public class CreditPortfolioClient {

    private static final Logger log = LoggerFactory.getLogger(CreditPortfolioClient.class);

    private static final ParameterizedTypeReference<List<CreditAccount>> LIST =
            new ParameterizedTypeReference<>() {};

    private final WebClient webClient;

    public CreditPortfolioClient(@Qualifier("creditPortfolioWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    /**
     * La línea revolvente activa del distribuidor.
     *
     * <p>Vacío no es un error: es quien pidió crédito para sí y nunca va a colocar. La app lo
     * traduce a esconder el módulo entero.
     */
    public Optional<CreditAccount> findActiveDistributorLine(UUID distributorPartyId) {
        return accountsOf(distributorPartyId).stream()
                .filter(a -> "DISTRIBUTOR_LINE".equalsIgnoreCase(a.productType()))
                .filter(a -> "ACTIVE".equalsIgnoreCase(a.status()))
                .findFirst();
    }

    public List<CreditAccount> accountsOf(UUID partyId) {
        try {
            List<CreditAccount> accounts = webClient.get()
                    .uri(uri -> uri.path("/api/v1/portfolio/accounts")
                            .queryParam("partyId", partyId)
                            .build())
                    // credit-portfolio resuelve el titular del header que normalmente inyecta el
                    // gateway. Aquí la llamada es servicio a servicio, así que se pone a mano —
                    // sin él responde 401 y la línea del distribuidor sería invisible.
                    .header("X-User-Id", partyId.toString())
                    .retrieve()
                    .bodyToMono(LIST)
                    .block();
            return accounts == null ? List.of() : accounts;
        } catch (RuntimeException e) {
            // Sin cartera no se puede colocar, pero tampoco se puede afirmar que el distribuidor
            // no tenga línea: se propaga para que el llamador falle con causa y no con un vacío
            // que parecería una respuesta legítima.
            log.error("credit-portfolio no respondió para partyId={}", partyId, e);
            throw e;
        }
    }

    private static final ParameterizedTypeReference<List<Installment>> CUOTAS =
            new ParameterizedTypeReference<>() {};

    private static final ParameterizedTypeReference<List<ClientProfile>> PROFILES = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<StoredFlag>> FLAGS = new ParameterizedTypeReference<>() {};

    // ── The distributor's clients: type, red list, limits ──────────────────────────────────────

    /**
     * What this client may take on her line: his type, the branch's limits for it and, with an amount and a term,
     * whether the portfolio would authorize it. Empty when the portfolio does not answer: it decides again at
     * authorization, so its silence never lets a placement through.
     */
    public Optional<PlacementLimits> placementLimits(UUID creditAccountId, UUID beneficiaryPartyId, BigDecimal amount,
                                                     Integer termPeriods, UUID asUser) {
        try {
            return Optional.ofNullable(webClient.get()
                    .uri(uri -> uri.path("/api/v1/portfolio/accounts/{id}/placement-limits")
                            .queryParam("beneficiaryPartyId", beneficiaryPartyId)
                            .queryParamIfPresent("amount", Optional.ofNullable(amount))
                            .queryParamIfPresent("termPeriods", Optional.ofNullable(termPeriods))
                            .build(creditAccountId))
                    .header("X-User-Id", asUser.toString())
                    .retrieve()
                    .bodyToMono(PlacementLimits.class)
                    .block());
        } catch (RuntimeException e) {
            log.warn("credit-portfolio no dio los límites del cliente {} en la línea {}: {}", beneficiaryPartyId, creditAccountId, e.getMessage());
            return Optional.empty();
        }
    }

    /** The profiles of several clients (her directory); empty when the portfolio does not answer. */
    public List<ClientProfile> profiles(List<UUID> beneficiaryPartyIds, UUID asUser) {
        if (beneficiaryPartyIds.isEmpty()) return List.of();
        try {
            List<ClientProfile> profiles = webClient.get()
                    .uri(uri -> uri.path("/api/v1/portfolio/beneficiaries/profiles")
                            .queryParam("partyIds", beneficiaryPartyIds.toArray())
                            .build())
                    .header("X-User-Id", asUser.toString())
                    .retrieve()
                    .bodyToMono(PROFILES)
                    .block();
            return profiles == null ? List.of() : profiles;
        } catch (RuntimeException e) {
            log.warn("credit-portfolio no dio los perfiles de {} clientes: {}", beneficiaryPartyIds.size(), e.getMessage());
            return List.of();
        }
    }

    public Optional<ClientProfile> profile(UUID beneficiaryPartyId, UUID asUser) {
        return profiles(List.of(beneficiaryPartyId), asUser).stream().findFirst();
    }

    /** Every mark ever put on the client, newest first. */
    public List<StoredFlag> flags(UUID beneficiaryPartyId, UUID asUser) {
        List<StoredFlag> flags = webClient.get()
                .uri("/api/v1/portfolio/beneficiaries/{id}/flags", beneficiaryPartyId)
                .header("X-User-Id", asUser.toString())
                .retrieve()
                .bodyToMono(FLAGS)
                .block();
        return flags == null ? List.of() : flags;
    }

    /** She reports her client: the mark goes to the red list with her reason and comments. */
    public StoredFlag report(UUID beneficiaryPartyId, UUID distributorPartyId, UUID creditAccountId, String reason, String comment) {
        log.info("-> POST credit-portfolio /beneficiaries/{}/flags motivo={} por {}", beneficiaryPartyId, reason, distributorPartyId);
        return webClient.post()
                .uri("/api/v1/portfolio/beneficiaries/{id}/flags", beneficiaryPartyId)
                .header("X-User-Id", distributorPartyId.toString())
                .bodyValue(new ReportBody(reason, comment, "DISTRIBUTOR", distributorPartyId, creditAccountId, distributorPartyId.toString()))
                .retrieve()
                .bodyToMono(StoredFlag.class)
                .block();
    }

    public StoredFlag clearFlag(UUID beneficiaryPartyId, UUID flagId, UUID clearedBy, String comment) {
        log.info("-> POST credit-portfolio /beneficiaries/{}/flags/{}/clear por {}", beneficiaryPartyId, flagId, clearedBy);
        return webClient.post()
                .uri("/api/v1/portfolio/beneficiaries/{id}/flags/{flag}/clear", beneficiaryPartyId, flagId)
                .header("X-User-Id", clearedBy.toString())
                .bodyValue(new ClearBody(comment, clearedBy.toString()))
                .retrieve()
                .bodyToMono(StoredFlag.class)
                .block();
    }

    record ReportBody(String reason, String comment, String source, UUID distributorPartyId, UUID creditAccountId, String raisedBy) {}

    record ClearBody(String comment, String clearedBy) {}

    /** A red-list mark as the portfolio shows it: derived (no flagId) or stored (a report). */
    public record ClientFlag(UUID flagId, String reason, String source, Instant since, UUID distributorPartyId,
                             UUID creditAccountId, String detail, String comment) {}

    public record ClientProfile(UUID beneficiaryPartyId, String type, List<ClientFlag> flags, int loansGranted, int loansPaid,
                                int activeLoans, BigDecimal outstanding, List<LineStanding> lines) {

        public List<ClientFlag> flagsOrEmpty() { return flags == null ? List.of() : flags; }

        public List<LineStanding> linesOrEmpty() { return lines == null ? List.of() : lines; }
    }

    public record LineStanding(UUID creditAccountId, UUID distributorPartyId, int activeLoans, BigDecimal outstanding,
                               boolean voucherOverdue) {}

    public record Limits(BigDecimal maxLoanAmount, BigDecimal maxOutstanding, Integer maxActiveLoans, Integer maxTerm) {}

    public record PlacementLimits(UUID beneficiaryPartyId, String clientType, boolean policyConfigured, String inheritedFrom,
                                  Limits limits, int activeLoans, BigDecimal outstanding, BigDecimal amountAvailable,
                                  Integer loansAvailable, List<ClientFlag> flags, boolean canPlace, String reason) {

        public List<ClientFlag> flagsOrEmpty() { return flags == null ? List.of() : flags; }
    }

    /** A stored mark with its history (who put it, who cleared it and why). */
    public record StoredFlag(UUID flagId, UUID beneficiaryPartyId, String reason, String source, UUID distributorPartyId,
                             UUID creditAccountId, String detail, String comment, Instant raisedAt, String raisedBy,
                             Instant clearedAt, String clearedBy, String clearComment, boolean active) {}

    /**
     * El avance del calendario de una disposición: cuántas quincenas van y cuántos días de mora.
     *
     * <p>credit-portfolio devuelve la <b>lista de cuotas</b>
     * ({@code CreditAccountController.dispositionSchedule} → {@code List<Installment>}), no un
     * resumen. Antes se deserializaba como objeto: Jackson fallaba en cada llamada, el
     * {@code catch} lo degradaba a "sin calendario" y la colocación salía con
     * {@code daysPastDue = 0}. Es decir, una colocación vencida se pintaba <b>AL CORRIENTE</b> en
     * la app, y como la comisión sólo se devenga si {@code late == 0}, también se devengaba de
     * más. El resumen se deriva aquí, que es donde se necesita.
     */
    public Optional<DispositionSchedule> scheduleOf(UUID creditAccountId, UUID dispositionId) {
        List<Installment> installments;
        try {
            installments = webClient.get()
                    .uri("/api/v1/portfolio/accounts/{a}/dispositions/{d}/schedule",
                            creditAccountId, dispositionId)
                    .header("X-User-Id", creditAccountId.toString())
                    .retrieve()
                    .bodyToMono(CUOTAS)
                    .block();
        } catch (RuntimeException e) {
            // Aquí se distingue lo que antes se confundía. "No pude leer el calendario" no es
            // "esta colocación está al corriente": devolver vacío haría que un fallo de
            // integración se viera como un dato bueno. Se propaga para que el rojo aparezca
            // donde está la causa.
            log.error("no pude leer el calendario de la disposición {} de la cuenta {}",
                    dispositionId, creditAccountId, e);
            throw e;
        }
        // Sin cuotas sí es un vacío legítimo: la disposición existe pero aún no tiene calendario.
        if (installments == null || installments.isEmpty()) {
            log.warn("la disposición {} todavía no tiene calendario", dispositionId);
            return Optional.empty();
        }
        return Optional.of(summarize(dispositionId, installments));
    }

    /** Deriva el resumen que consume la fila de la colocación a partir de las cuotas reales. */
    private static DispositionSchedule summarize(UUID dispositionId, List<Installment> installments) {
        LocalDate hoy = BusinessClock.current().today();
        int paid = (int) installments.stream().filter(Installment::pagada).count();

        // El atraso es el de la cuota vencida más antigua sin pagar: es el criterio con el que
        // cartera calcula `days_delinquent`, y las dos cifras tienen que contar lo mismo.
        int daysPastDue = installments.stream()
                .filter(c -> !c.pagada() && c.dueDate() != null && c.dueDate().isBefore(hoy))
                .mapToInt(c -> (int) java.time.temporal.ChronoUnit.DAYS.between(c.dueDate(), hoy))
                .max().orElse(0);

        LocalDate nextDueDate = installments.stream()
                .filter(c -> !c.pagada() && c.dueDate() != null)
                .map(Installment::dueDate)
                .min(LocalDate::compareTo).orElse(null);

        BigDecimal installmentAmount = installments.stream()
                .map(Installment::totalAmount).filter(java.util.Objects::nonNull)
                .findFirst().orElse(BigDecimal.ZERO);

        BigDecimal balance = installments.stream()
                .filter(c -> !c.pagada())
                .map(Installment::totalAmount).filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new DispositionSchedule(dispositionId, paid, installments.size(),
                nextDueDate, installmentAmount, daysPastDue, balance);
    }

    /** Una cuota tal como la publica credit-portfolio. */
    public record Installment(
            UUID       installmentId,
            UUID       scheduleId,
            Integer    installmentNumber,
            LocalDate  dueDate,
            BigDecimal principalAmount,
            BigDecimal interestAmount,
            BigDecimal taxAmount,
            BigDecimal totalAmount,
            String     status) {
        boolean pagada() { return "PAID".equalsIgnoreCase(status); }
    }

    public record CreditAccount(
            UUID       creditAccountId,
            String     productCode,
            String     productType,
            String     productBehavior,
            UUID       obligorPartyId,
            String     status,
            BigDecimal nominalRate,
            BigDecimal creditLimit,
            BigDecimal principalBalance,
            BigDecimal accruedInterestBalance,
            BigDecimal availableCredit,
            BigDecimal totalDebt,
            String     clabeAccount,
            int        daysDelinquent,
            Instant    createdAt,
            Instant    activatedAt,
            Integer    paidInstallments,
            Integer    totalInstallments,
            LocalDate  paymentDueDate,
            BigDecimal minimumPayment,
            BigDecimal overdueAmount,
            /** A distributor line: whether it may place today, and why not; null on every other product. */
            Boolean    placementBlocked,
            String     placementBlockReason,
            BigDecimal overdueStatementsAmount,
            /** Her share of what her clients pay (0.18) and how it is settled (NETTED); null on every other product. */
            BigDecimal distributorCommissionRate,
            String     distributorCommissionSettlement) {

        public boolean blockedForPlacements() { return Boolean.TRUE.equals(placementBlocked); }
    }

    public record DispositionSchedule(
            UUID       dispositionId,
            Integer    paidInstallments,
            Integer    totalInstallments,
            LocalDate  nextDueDate,
            BigDecimal installmentAmount,
            Integer    daysPastDue,
            BigDecimal outstandingBalance) {}
}
