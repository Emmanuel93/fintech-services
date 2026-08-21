package com.fintech.channelbackoffice.infrastructure.adapter.in.api;

import com.fintech.channelbackoffice.application.CommercialPortfolioService;
import com.fintech.channelbackoffice.application.CommercialScopeService;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.CommissionClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.IdentityClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.PartyClient;
import org.springframework.mock.web.MockHttpServletRequest;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.CreditPortfolioClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.CreditPortfolioClient.CreditAccountResponse;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.SalesOrgClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.math.BigDecimal;
import java.util.ArrayList;
import org.junit.jupiter.api.DisplayName;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DashboardControllerTest {

    private final CreditPortfolioClient portfolio = mock(CreditPortfolioClient.class);
    private final SalesOrgClient salesOrg = mock(SalesOrgClient.class);
    private final CommissionClient commission = mock(CommissionClient.class);
    private final PartyClient party = mock(PartyClient.class);
    private final IdentityClient identity = mock(IdentityClient.class);
    // Alcance y rollup van reales y se apoyan en el mismo `salesOrg` ya simulado: son las reglas
    // que estas pruebas comprueban —qué unidad puede ver cada quién— y simularlas las vaciaría.
    private final DashboardController controller = new DashboardController(
            portfolio, salesOrg, commission, identity,
            new CommercialPortfolioService(salesOrg, party, portfolio),
            new CommercialScopeService(salesOrg));

    /**
     * Sólo se usa para leer el bearer con el que se pide el directorio de nombres.
     *
     * <p>Lleva Authorization aunque el directorio esté simulado: el controlador extrae el token
     * antes de llamar, y sin encabezado corta con 401 sin llegar a la lógica de alcance que estas
     * pruebas comprueban.
     */
    private static MockHttpServletRequest http() {
        var req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer token-de-prueba");
        return req;
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateAs(String role, String principal) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        principal, "n/a", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    // ── summary: byExecutive retirado ─────────────────────────────────────────

    @Test
    void summary_noLongerExposesByExecutive() {
        when(portfolio.summary()).thenReturn(null);
        when(portfolio.productMix()).thenReturn(List.of());

        Map<String, Object> body = controller.summary().getBody();

        assertThat(body).isNotNull();
        assertThat(body).doesNotContainKey("byExecutive");
        assertThat(body).containsKey("capitalColocado");
    }

    // ── commercial: alcance por unidad ────────────────────────────────────────

    @Test
    void commercial_orgWideRole_seesAnyUnit() {
        UUID unit = UUID.randomUUID();
        authenticateAs("ADMIN", UUID.randomUUID().toString());
        when(salesOrg.scope(unit)).thenReturn(List.of(
                Map.of("assigneeType", "STAFF", "assigneeId", UUID.randomUUID().toString())));

        Map<String, Object> body = controller.commercial(unit, http()).getBody();

        assertThat(body).isNotNull();
        assertThat(body).containsEntry("unitId", unit.toString());
        assertThat(body).containsEntry("executiveCount", 1L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void commercial_includesDistributorCartera_scopedToSubtree() {
        UUID unit = UUID.randomUUID();
        UUID distId = UUID.randomUUID();
        UUID acctId = UUID.randomUUID();
        authenticateAs("ADMIN", UUID.randomUUID().toString());
        when(salesOrg.scope(unit)).thenReturn(List.of());
        when(salesOrg.distributorsInSubtree(unit)).thenReturn(List.of(distId));
        when(commission.creditsByPromoters(List.of(distId))).thenReturn(List.of(
                new CommissionClient.PromoterCredit(acctId, distId, "SME_LOAN")));
        when(portfolio.batch(List.of(acctId))).thenReturn(List.of(account(acctId)));

        Map<String, Object> body = controller.commercial(unit, http()).getBody();

        assertThat(body).containsEntry("distributorCount", 1);
        List<Map<String, Object>> cartera = (List<Map<String, Object>>) body.get("distributorCartera");
        assertThat(cartera).hasSize(1);
        assertThat(cartera.get(0)).containsEntry("creditAccountId", acctId.toString());
    }

    private static CreditAccountResponse account(UUID id) {
        return new CreditAccountResponse(
                id, "CT-1", UUID.randomUUID(), "SME-STD", "SME_LOAN", "INSTALLMENT", "ACTIVE",
                new java.math.BigDecimal("0.24"), 12, new java.math.BigDecimal("100000"),
                java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, null, null, null, 0, "LOW", null, null, null,
                0, 12, 1, null, null, null, null, null);
    }

    @Test
    void commercial_orgWideRole_withoutUnitId_returns400() {
        authenticateAs("ADMIN", UUID.randomUUID().toString());

        assertThatThrownBy(() -> controller.commercial(null, http()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void commercial_commercialUser_defaultsToOwnUnit() {
        UUID myUnit = UUID.randomUUID();
        UUID me = UUID.randomUUID();
        authenticateAs("EXECUTIVE", me.toString());
        when(salesOrg.currentAssignment("STAFF", me)).thenReturn(Map.of("unitId", myUnit.toString()));
        when(salesOrg.scope(myUnit)).thenReturn(List.of());

        Map<String, Object> body = controller.commercial(null, http()).getBody();

        assertThat(body).containsEntry("unitId", myUnit.toString());
    }

    @Test
    void commercial_commercialUser_requestingUnitInOwnSubtree_isAllowed() {
        UUID myUnit = UUID.randomUUID();
        UUID childUnit = UUID.randomUUID();
        UUID me = UUID.randomUUID();
        authenticateAs("EXECUTIVE", me.toString());
        when(salesOrg.currentAssignment("STAFF", me)).thenReturn(Map.of("unitId", myUnit.toString()));
        when(salesOrg.subtree(myUnit)).thenReturn(List.of(
                Map.of("unitId", myUnit.toString()),
                Map.of("unitId", childUnit.toString())));
        when(salesOrg.scope(childUnit)).thenReturn(List.of());

        Map<String, Object> body = controller.commercial(childUnit, http()).getBody();

        assertThat(body).containsEntry("unitId", childUnit.toString());
    }

    @Test
    void commercial_commercialUser_requestingUnitOutOfScope_returns403() {
        UUID myUnit = UUID.randomUUID();
        UUID otherUnit = UUID.randomUUID();
        UUID me = UUID.randomUUID();
        authenticateAs("EXECUTIVE", me.toString());
        when(salesOrg.currentAssignment("STAFF", me)).thenReturn(Map.of("unitId", myUnit.toString()));
        when(salesOrg.subtree(myUnit)).thenReturn(List.of(Map.of("unitId", myUnit.toString())));

        assertThatThrownBy(() -> controller.commercial(otherUnit, http()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void commercial_commercialUser_withoutAssignment_returns403() {
        UUID me = UUID.randomUUID();
        authenticateAs("EXECUTIVE", me.toString());
        when(salesOrg.currentAssignment("STAFF", me)).thenReturn(null);

        assertThatThrownBy(() -> controller.commercial(UUID.randomUUID(), http()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    // ── Rollup por unidad de ORIGEN ──────────────────────────────────────────────────────────

    private static Map<String, Object> unit(UUID id, String code, String name) {
        return Map.of("unitId", id.toString(), "code", code, "name", name);
    }

    private static CreditPortfolioClient.OriginUnitStat originStat(
            String code, long accounts, long delinquent,
            String principal, String s1, String s2, String s3) {
        return new CreditPortfolioClient.OriginUnitStat(code, accounts, delinquent,
                new BigDecimal(principal), new BigDecimal(s1), new BigDecimal(s2), new BigDecimal(s3));
    }

    @Test
    @DisplayName("el rollup por origen cuesta 2 llamadas: el subárbol y el agregado")
    void originRollupIsConstantCost() {
        authenticateAs("ADMIN", UUID.randomUUID().toString());
        UUID root = UUID.randomUUID();
        // La raíz se resuelve buscando la unidad sin padre en listUnits().
        when(salesOrg.listUnits()).thenReturn(List.of(
                Map.of("unitId", root.toString(), "code", "MX", "name", "Nacional")));

        // Un subárbol de 30 unidades: el número de llamadas no puede depender de esto.
        List<Map<String, Object>> subtree = new ArrayList<>();
        subtree.add(unit(root, "MX", "Nacional"));
        for (int i = 0; i < 29; i++) {
            subtree.add(unit(UUID.randomUUID(), "E_" + i, "Ejecutivo " + i));
        }
        when(salesOrg.subtree(root)).thenReturn(subtree);
        when(portfolio.statsByOriginUnit(any())).thenReturn(List.of(
                originStat("E_0", 3, 1, "180000", "100000", "50000", "30000")));

        var response = controller.commercialByOriginUnit(root);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(salesOrg, times(1)).subtree(root);
        verify(portfolio, times(1)).statsByOriginUnit(any());
        verifyNoMoreInteractions(portfolio);
    }

    @Test
    @DisplayName("el total del subárbol es la suma de sus unidades")
    void parentEqualsSumOfChildren() {
        authenticateAs("ADMIN", UUID.randomUUID().toString());
        UUID root = UUID.randomUUID();
        // La raíz se resuelve buscando la unidad sin padre en listUnits().
        when(salesOrg.listUnits()).thenReturn(List.of(
                Map.of("unitId", root.toString(), "code", "MX", "name", "Nacional")));
        when(salesOrg.subtree(root)).thenReturn(List.of(
                unit(root, "MX", "Nacional"),
                unit(UUID.randomUUID(), "S_A", "Sucursal A"),
                unit(UUID.randomUUID(), "S_B", "Sucursal B")));
        when(portfolio.statsByOriginUnit(any())).thenReturn(List.of(
                originStat("S_A", 3, 2, "180000", "100000", "50000", "30000"),
                originStat("S_B", 2, 1, "100000",  "80000",     "0", "20000")));

        var body = controller.commercialByOriginUnit(root).getBody();

        @SuppressWarnings("unchecked")
        Map<String, Object> total = (Map<String, Object>) body.get("total");
        assertThat((BigDecimal) total.get("principal")).isEqualByComparingTo("280000");
        assertThat(total.get("accounts")).isEqualTo(5L);
        assertThat(total.get("delinquentAccounts")).isEqualTo(3L);
        // Vencida oficial = 90+ solamente; el 31–90 va por su cuenta y no se suma a la mora.
        assertThat((BigDecimal) total.get("carteraVencida")).isEqualByComparingTo("50000");
        assertThat((BigDecimal) total.get("atrasoTemprano")).isEqualByComparingTo("50000");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byUnit = (List<Map<String, Object>>) body.get("byUnit");
        BigDecimal suma = byUnit.stream().map(u -> (BigDecimal) u.get("principal"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(suma).isEqualByComparingTo((BigDecimal) total.get("principal"));
    }

    @Test
    @DisplayName("la respuesta declara su atribución, para que no se confunda con la comercial")
    void originRollupDeclaresItsAttribution() {
        authenticateAs("ADMIN", UUID.randomUUID().toString());
        UUID root = UUID.randomUUID();
        // La raíz se resuelve buscando la unidad sin padre en listUnits().
        when(salesOrg.listUnits()).thenReturn(List.of(
                Map.of("unitId", root.toString(), "code", "MX", "name", "Nacional")));
        when(salesOrg.subtree(root)).thenReturn(List.of(unit(root, "MX", "Nacional")));
        when(portfolio.statsByOriginUnit(any())).thenReturn(List.of());

        var body = controller.commercialByOriginUnit(root).getBody();

        assertThat(body.get("attribution")).isEqualTo("ORIGIN_UNIT");
        assertThat((String) body.get("attributionNote")).contains("sellada");
    }

    @Test
    @DisplayName("cada unidad llega con su nombre resuelto del subárbol, sin consulta extra")
    void unitsCarryTheirName() {
        authenticateAs("ADMIN", UUID.randomUUID().toString());
        UUID root = UUID.randomUUID();
        // La raíz se resuelve buscando la unidad sin padre en listUnits().
        when(salesOrg.listUnits()).thenReturn(List.of(
                Map.of("unitId", root.toString(), "code", "MX", "name", "Nacional")));
        when(salesOrg.subtree(root)).thenReturn(List.of(
                unit(root, "MX", "Nacional"),
                unit(UUID.randomUUID(), "S_PAC", "Sucursal Pachuca")));
        when(portfolio.statsByOriginUnit(any())).thenReturn(List.of(
                originStat("S_PAC", 1, 0, "1000", "1000", "0", "0")));

        var body = controller.commercialByOriginUnit(root).getBody();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byUnit = (List<Map<String, Object>>) body.get("byUnit");
        assertThat(byUnit).singleElement()
                .satisfies(u -> assertThat(u.get("unitName")).isEqualTo("Sucursal Pachuca"));
    }
}
