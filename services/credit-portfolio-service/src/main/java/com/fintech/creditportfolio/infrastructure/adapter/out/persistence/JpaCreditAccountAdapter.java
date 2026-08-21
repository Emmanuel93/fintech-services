package com.fintech.creditportfolio.infrastructure.adapter.out.persistence;

import com.fintech.creditportfolio.application.port.out.CreditAccountRepository;
import com.fintech.creditportfolio.application.port.out.OriginUnitStat;
import com.fintech.creditportfolio.application.port.out.PortfolioStat;
import com.fintech.creditportfolio.application.port.out.PortfolioSummary;
import com.fintech.creditportfolio.application.port.out.ProductMix;
import com.fintech.creditportfolio.domain.CreditAccount;
import com.fintech.creditportfolio.domain.CreditAccountStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaCreditAccountAdapter implements CreditAccountRepository {

    private final SpringDataCreditAccountRepository jpa;
    private final SpringDataInstallmentRepository installments;

    JpaCreditAccountAdapter(SpringDataCreditAccountRepository jpa,
                            SpringDataInstallmentRepository installments) {
        this.jpa = jpa;
        this.installments = installments;
    }

    @Override public CreditAccount save(CreditAccount a) { return jpa.save(a); }
    @Override public Optional<CreditAccount> findById(UUID id) { return jpa.findById(id); }
    @Override public Optional<CreditAccount> findByContractId(UUID contractId) { return jpa.findByContractId(contractId); }
    @Override public List<CreditAccount> findByObligorPartyId(UUID obligorPartyId) { return jpa.findByObligorPartyId(obligorPartyId); }
    @Override public List<CreditAccount> findAllByStatus(CreditAccountStatus status) { return jpa.findByStatus(status); }

    @Override
    public Page<CreditAccount> search(CreditAccountStatus status, String productType,
                                       String q, Integer minDaysDelinquent, Integer maxDaysDelinquent,
                                       Collection<UUID> partyIds, Pageable pageable) {
        // Un texto en blanco no es un filtro: se normaliza a null para que la
        // consulta lo ignore, en vez de buscar contratos que contengan "".
        // Patrón listo para el LIKE: en minúsculas y con comodines, para que la
        // consulta no tenga que envolver el parámetro en LOWER().
        String text = (q == null || q.isBlank()) ? null
                : "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
        String type = (productType == null || productType.isBlank()) ? null : productType;
        // Colección vacía = sin filtro; se pasa null para que la guarda IS NULL
        // de la consulta la ignore (un IN vacío no filtraría nada útil).
        Collection<UUID> parties = (partyIds == null || partyIds.isEmpty()) ? null : partyIds;
        return jpa.search(status, type, text, minDaysDelinquent, maxDaysDelinquent, parties, pageable);
    }

    @Override
    public List<CreditAccount> findByCreditAccountIdIn(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return jpa.findByCreditAccountIdIn(ids);
    }

    @Override
    public List<PortfolioStat> stats(String groupBy) {
        List<Object[]> rows = switch (groupBy == null ? "" : groupBy) {
            case "status"      -> jpa.statsByStatus();
            case "productType" -> jpa.statsByProductType();
            case "dpdBucket"   -> jpa.statsByDpdBucket();
            default -> throw new IllegalArgumentException(
                    "groupBy inválido: '" + groupBy + "' (esperado: status | productType | dpdBucket)");
        };
        return rows.stream()
                .map(r -> new PortfolioStat((String) r[0], num(r[1]).longValue(), dec(r[2])))
                .toList();
    }

    @Override
    public List<OriginUnitStat> statsByOriginUnit(java.util.Collection<String> unitCodes) {
        // Sin códigos se usa la consulta sin filtro: en SQL nativo no se puede pasar una colección
        // nula —Postgres no infiere su tipo— y un `IN ()` vacío tampoco es SQL válido.
        List<Object[]> rows = (unitCodes == null || unitCodes.isEmpty())
                ? jpa.statsByOriginUnitRows()
                : jpa.statsByOriginUnitRows(java.util.List.copyOf(unitCodes));
        return rows.stream()
                .map(r -> new OriginUnitStat(
                        (String) r[0],
                        num(r[1]).longValue(),
                        num(r[2]).longValue(),
                        dec(r[3]), dec(r[4]), dec(r[5]), dec(r[6])))
                .toList();
    }

    @Override
    public PortfolioSummary summary() {
        Object[] r = jpa.summaryRow();
        // La consulta devuelve una sola fila; según el driver, Hibernate la
        // entrega plana o envuelta en otro arreglo.
        Object[] row = (r.length == 1 && r[0] instanceof Object[] inner) ? inner : r;

        // Cobro por periodo (mes calendario): actual y próximo. Bordes calculados aquí y pasados a la
        // consulta, para no atar la fecha a la base.
        LocalDate today = LocalDate.now();
        LocalDate curStart = today.withDayOfMonth(1);
        LocalDate curEnd = today.withDayOfMonth(today.lengthOfMonth());
        LocalDate nextStart = curStart.plusMonths(1);
        LocalDate nextEnd = nextStart.withDayOfMonth(nextStart.lengthOfMonth());
        Object[] pr = installments.periodCollectionRow(curStart, curEnd, nextStart, nextEnd);
        Object[] p = (pr.length == 1 && pr[0] instanceof Object[] inner2) ? inner2 : pr;

        return new PortfolioSummary(
                num(row[0]).longValue(),
                num(row[1]).longValue(),
                dec(row[2]), dec(row[3]),
                dec(row[4]), dec(row[5]), dec(row[6]),
                num(row[7]).longValue(),
                dec(p[0]), dec(p[1]), dec(p[2]));
    }

    @Override
    public List<ProductMix> productMix() {
        return jpa.productMixRows().stream()
                .map(r -> new ProductMix(
                        (String) r[0], (String) r[1],
                        num(r[2]).longValue(), dec(r[3]), dec(r[4])))
                .toList();
    }

    private static Number num(Object o) { return o instanceof Number n ? n : 0; }

    private static BigDecimal dec(Object o) {
        return o instanceof BigDecimal b ? b
             : o instanceof Number n ? BigDecimal.valueOf(n.doubleValue())
             : BigDecimal.ZERO;
    }
}
