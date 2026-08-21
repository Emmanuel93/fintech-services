package com.fintech.channelbackoffice.application;

import org.junit.jupiter.api.Test;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.IdentityClient;

import java.util.List;
import java.util.Set;

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
    void matrix_isPopulated_andReloadRebuilds() {
        assertThat(service.matrix()).containsKey("ADMIN");
        assertThat(service.matrix().get("ADMIN")).contains("audit.view", "salesorg.manage", "applications.decide");

        service.reload();
        assertThat(service.matrix()).containsKey("ADMIN");
    }
}
