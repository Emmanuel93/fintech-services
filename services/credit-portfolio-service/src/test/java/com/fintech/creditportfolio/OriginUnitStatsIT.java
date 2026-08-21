package com.fintech.creditportfolio;

import com.fintech.creditportfolio.application.port.out.CreditAccountRepository;
import com.fintech.creditportfolio.application.port.out.OriginUnitStat;
import com.fintech.creditportfolio.domain.CreditAccount;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El rollup por unidad de origen, contra Postgres real.
 *
 * <p>Lo que hay que verificar es lo que un mock no puede: que el {@code GROUP BY} con
 * {@code FILTER} sea SQL válido, que el filtro por colección vacía no genere un {@code IN ()}, y
 * —lo que de verdad importa— que <b>la suma de las unidades sea igual al total</b>. Si esa
 * igualdad se rompe, el árbol comercial deja de cuadrar consigo mismo a distintas alturas y nadie
 * sabe cuál cifra llevar a un comité.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class OriginUnitStatsIT {

    @Autowired CreditAccountRepository repository;
    @Autowired EntityManager em;

    private String unitA;
    private String unitB;

    @BeforeEach
    void seed() {
        // Códigos únicos por corrida: la suite comparte contenedor y otras pruebas siembran cuentas.
        unitA = "E_TEST_A_" + UUID.randomUUID().toString().substring(0, 8);
        unitB = "E_TEST_B_" + UUID.randomUUID().toString().substring(0, 8);

        account(unitA, "100000", 0);    // al corriente  → stage1
        account(unitA, "50000",  45);   // atraso 31-90  → stage2
        account(unitA, "30000",  120);  // vencida 90+   → stage3
        account(unitB, "80000",  0);
        account(unitB, "20000",  200);
        em.flush();
    }

    @Test
    @DisplayName("agrupa por unidad y separa los tres tramos IFRS-9")
    void groupsByUnitAndSplitsStages() {
        Map<String, OriginUnitStat> byCode = statsOf(List.of(unitA, unitB));

        OriginUnitStat a = byCode.get(unitA);
        assertThat(a.accounts()).isEqualTo(3);
        assertThat(a.principal()).isEqualByComparingTo("180000");
        assertThat(a.stage1Principal()).isEqualByComparingTo("100000");
        assertThat(a.stage2Principal()).isEqualByComparingTo("50000");
        assertThat(a.stage3Principal()).isEqualByComparingTo("30000");
        // Dos de las tres tienen atraso.
        assertThat(a.delinquentAccounts()).isEqualTo(2);
    }

    @Test
    @DisplayName("la suma de las unidades iguala el total del subárbol")
    void unitsAddUpToTheParent() {
        List<OriginUnitStat> rows = repository.statsByOriginUnit(List.of(unitA, unitB));

        BigDecimal sumaUnidades = rows.stream().map(OriginUnitStat::principal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long sumaCuentas = rows.stream().mapToLong(OriginUnitStat::accounts).sum();

        // 180.000 de A + 100.000 de B. Cada cuenta se atribuye a UNA sola unidad de origen, así
        // que el padre es exactamente la suma de sus descendientes — sin dobles conteos.
        assertThat(sumaUnidades).isEqualByComparingTo("280000");
        assertThat(sumaCuentas).isEqualTo(5);
    }

    @Test
    @DisplayName("filtrar por una unidad excluye a la otra")
    void filteringNarrowsToTheRequestedUnits() {
        Map<String, OriginUnitStat> soloA = statsOf(List.of(unitA));

        assertThat(soloA).containsOnlyKeys(unitA);
        assertThat(soloA.get(unitA).principal()).isEqualByComparingTo("180000");
    }

    @Test
    @DisplayName("sin filtro devuelve todas las unidades, incluidas las sembradas por otros")
    void noFilterReturnsEveryUnit() {
        List<OriginUnitStat> todas = repository.statsByOriginUnit(null);

        assertThat(todas).extracting(OriginUnitStat::unitCode).contains(unitA, unitB);
        // Colección vacía = sin filtro, igual que null. Es el caso que produce un IN () inválido
        // si el adaptador no lo normaliza.
        assertThat(repository.statsByOriginUnit(List.of()))
                .extracting(OriginUnitStat::unitCode).contains(unitA, unitB);
    }

    @Test
    @DisplayName("una unidad que no existe devuelve vacío, no todas")
    void anUnknownUnitReturnsNothing() {
        assertThat(repository.statsByOriginUnit(List.of("E_NO_EXISTE_" + UUID.randomUUID()))).isEmpty();
    }

    // ── Semilla ──────────────────────────────────────────────────────────────────────────────

    private Map<String, OriginUnitStat> statsOf(List<String> codes) {
        return repository.statsByOriginUnit(codes).stream()
                .collect(Collectors.toMap(OriginUnitStat::unitCode, s -> s));
    }

    /**
     * Una cuenta activa sellada en su unidad de origen.
     *
     * <p>El saldo y los días de atraso se fijan por UPDATE porque son estado que el motor mueve
     * con eventos de balance; forzarlos aquí mantiene la semilla legible y no depende de simular
     * pagos para llegar a un tramo.
     */
    private void account(String unitCode, String principal, int daysDelinquent) {
        CreditAccount a = CreditAccount.fromSnapshot(
                UUID.randomUUID(), "CTR-" + UUID.randomUUID(), UUID.randomUUID(),
                "PL-TEST", 1, "PERSONAL_LOAN", "INSTALLMENT",
                new BigDecimal(principal), null, 12,
                new BigDecimal("0.28"), new BigDecimal("0.50"),
                "FRENCH", BigDecimal.ZERO, "032180000118359719", "BAJO", null, null);
        a.sealOriginUnit(unitCode);
        em.persist(a);
        em.createQuery("UPDATE CreditAccount a SET a.status = com.fintech.creditportfolio.domain."
                     + "CreditAccountStatus.ACTIVE, a.principalBalance = :p, a.daysDelinquent = :d "
                     + "WHERE a.creditAccountId = :id")
                .setParameter("p", new BigDecimal(principal))
                .setParameter("d", daysDelinquent)
                .setParameter("id", a.getCreditAccountId())
                .executeUpdate();
    }
}
