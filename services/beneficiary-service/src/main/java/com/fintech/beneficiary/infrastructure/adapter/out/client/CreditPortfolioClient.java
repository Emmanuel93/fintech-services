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
        List<Installment> cuotas;
        try {
            cuotas = webClient.get()
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
        if (cuotas == null || cuotas.isEmpty()) {
            log.warn("la disposición {} todavía no tiene calendario", dispositionId);
            return Optional.empty();
        }
        return Optional.of(resumir(dispositionId, cuotas));
    }

    /** Deriva el resumen que consume la fila de la colocación a partir de las cuotas reales. */
    private static DispositionSchedule resumir(UUID dispositionId, List<Installment> cuotas) {
        LocalDate hoy = LocalDate.now();
        int pagadas = (int) cuotas.stream().filter(Installment::pagada).count();

        // El atraso es el de la cuota vencida más antigua sin pagar: es el criterio con el que
        // cartera calcula `days_delinquent`, y las dos cifras tienen que contar lo mismo.
        int diasAtraso = cuotas.stream()
                .filter(c -> !c.pagada() && c.dueDate() != null && c.dueDate().isBefore(hoy))
                .mapToInt(c -> (int) java.time.temporal.ChronoUnit.DAYS.between(c.dueDate(), hoy))
                .max().orElse(0);

        LocalDate proxima = cuotas.stream()
                .filter(c -> !c.pagada() && c.dueDate() != null)
                .map(Installment::dueDate)
                .min(LocalDate::compareTo).orElse(null);

        BigDecimal montoCuota = cuotas.stream()
                .map(Installment::totalAmount).filter(java.util.Objects::nonNull)
                .findFirst().orElse(BigDecimal.ZERO);

        BigDecimal saldo = cuotas.stream()
                .filter(c -> !c.pagada())
                .map(Installment::totalAmount).filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new DispositionSchedule(dispositionId, pagadas, cuotas.size(),
                proxima, montoCuota, diasAtraso, saldo);
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
            BigDecimal overdueAmount) {}

    public record DispositionSchedule(
            UUID       dispositionId,
            Integer    paidInstallments,
            Integer    totalInstallments,
            LocalDate  nextDueDate,
            BigDecimal installmentAmount,
            Integer    daysPastDue,
            BigDecimal outstandingBalance) {}
}
