package com.fintech.channelbackoffice.application;

import org.junit.jupiter.api.Test;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.IdentityClient;

import java.util.List;
import java.util.Set;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PermissionsServiceTest {

    // Sin llamar a reload(): la matriz efectiva es el respaldo en código, que es
    // justo la política que estas pruebas fijan. Con identity de por medio estarían
    // comprobando la red, no la regla.
    private final PermissionsService service =
            new PermissionsService(mock(IdentityClient.class));

    @Test
    void auditor_canViewAuditAndAnalyze_butNotDecide() {
        Set<String> caps = service.capabilitiesFor(List.of("AUDITOR"));

        assertThat(caps).contains("audit.view", "applications.analyze");
        assertThat(caps).doesNotContain("applications.decide", "salesorg.manage");
    }

    @Test
    void underwriter_canDecide_butNotManageStructure() {
        Set<String> caps = service.capabilitiesFor(List.of("UNDERWRITER"));

        assertThat(caps).contains("applications.decide", "applications.analyze");
        assertThat(caps).doesNotContain("salesorg.manage", "audit.view");
    }

    @Test
    void capabilitiesAreUnionOfMultipleRoles() {
        Set<String> caps = service.capabilitiesFor(List.of("PRODUCT_MANAGER", "AUDITOR"));

        assertThat(caps).contains("products.manage", "audit.view");
    }

    @Test
    void unknownRole_hasNoCapabilities() {
        assertThat(service.capabilitiesFor(List.of("NOT_A_ROLE"))).isEmpty();
        assertThat(service.capabilitiesFor(null)).isEmpty();
    }

    @Test
    void opsSupervisor_managesSalesOrgAndAssignsExecutives() {
        Set<String> caps = service.capabilitiesFor(List.of("OPS_SUPERVISOR"));

        assertThat(caps).contains("salesorg.manage", "clients.assign-executive", "dashboard.commercial");
    }

    @Test
    void treasury_isReadByFinanceOpsAndAudit_butNotBySalesOrSupport() {
        // Mismo reparto que el seed `019`: tesorería, quien opera los pagos y el auditor.
        for (String role : List.of("ADMIN", "OPS_SUPERVISOR", "FINANCE", "AUDITOR")) {
            assertThat(service.capabilitiesFor(List.of(role))).as(role).contains("treasury.view");
        }
        for (String role : List.of("EXECUTIVE", "SUPPORT", "CREDIT_ANALYST", "COLLECTIONS_AGENT")) {
            assertThat(service.capabilitiesFor(List.of(role))).as(role).doesNotContain("treasury.view");
        }
    }

    @Test
    void matrix_isPopulated_andReloadRebuilds() {
        assertThat(service.matrix()).containsKey("ADMIN");
        assertThat(service.matrix().get("ADMIN")).contains("audit.view", "salesorg.manage", "applications.decide");

        service.reload();
        assertThat(service.matrix()).containsKey("ADMIN");
    }

    /**
     * The fallback is what applies while identity does not answer, which happens every time the stack is recreated:
     * a capability identity seeds and the fallback lacks is a 403 nobody can explain. Every (role, capability) that
     * identity's migrations grant must be in it.
     */
    @Test
    void theFallbackHoldsEveryCapabilityIdentitySeeds() throws Exception {
        Path seeds = Path.of("../identity-service/src/main/resources/db/changelog/identity");
        Pattern grant = Pattern.compile("\\(\\s*'([A-Z_]+)'\\s*,\\s*'([a-z.\\-]+)'\\s*,\\s*'seed'\\s*\\)");
        Map<String, Set<String>> respaldo = service.matrix();
        List<String> faltan = new ArrayList<>();
        try (var files = Files.list(seeds)) {
            for (Path f : files.filter(x -> x.toString().endsWith(".sql")).toList()) {
                Matcher m = grant.matcher(Files.readString(f));
                while (m.find()) {
                    if (!respaldo.getOrDefault(m.group(1), Set.of()).contains(m.group(2))) {
                        faltan.add(m.group(1) + " → " + m.group(2) + " (" + f.getFileName() + ")");
                    }
                }
            }
        }
        assertThat(faltan).as("capacidades que identity siembra y el respaldo no conoce").isEmpty();
    }
}
