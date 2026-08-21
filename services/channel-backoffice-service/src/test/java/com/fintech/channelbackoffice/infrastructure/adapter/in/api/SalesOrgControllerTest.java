package com.fintech.channelbackoffice.infrastructure.adapter.in.api;

import com.fintech.channelbackoffice.application.CommercialScopeService;
import com.fintech.channelbackoffice.application.PermissionsService;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.IdentityClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.SalesOrgClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SalesOrgControllerTest {

    private final SalesOrgClient client = mock(SalesOrgClient.class);
    // Permisos y alcance van reales, no simulados: lo que estas pruebas afirman es precisamente
    // quién puede escribir la estructura y sobre qué unidad. Con mocks devolviendo `true` a todo,
    // «prohibido para SUPPORT» pasaría sin comprobar nada.
    private final SalesOrgController controller = new SalesOrgController(
            client,
            new PermissionsService(mock(IdentityClient.class)),
            new CommercialScopeService(client));

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "staff-1", "n/a", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    // ── Lecturas abiertas a cualquier empleado ────────────────────────────────

    @Test
    void listLevels_delegatesWithoutRequiringAdmin() {
        when(client.listLevels()).thenReturn(List.of(Map.of("code", "NATIONAL", "depth", 0)));

        assertThat(controller.listLevels()).hasSize(1);
    }

    @Test
    void subtree_delegates() {
        UUID unitId = UUID.randomUUID();
        when(client.subtree(unitId)).thenReturn(List.of(Map.of("code", "MX")));

        assertThat(controller.subtree(unitId)).hasSize(1);
    }

    @Test
    void current_returnsNotFoundWhenNoActiveAssignment() {
        UUID id = UUID.randomUUID();
        when(client.currentAssignment("STAFF", id)).thenReturn(null);

        ResponseEntity<Map<String, Object>> resp = controller.current("STAFF", id);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── Escrituras gated a admin de estructura ────────────────────────────────

    @Test
    void createUnit_forbiddenForNonAdmin_andDomainNotTouched() {
        authenticateAs("SUPPORT");

        assertThatThrownBy(() -> controller.createUnit(Map.of("code", "NORTE")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
        verify(client, never()).createUnit(any());
    }

    @Test
    void createUnit_allowedForAdmin() {
        authenticateAs("ADMIN");
        Map<String, Object> body = Map.of("code", "NORTE");
        when(client.createUnit(body)).thenReturn(Map.of("code", "NORTE", "path", "MX.NORTE"));

        assertThat(controller.createUnit(body)).containsEntry("path", "MX.NORTE");
        verify(client).createUnit(body);
    }

    @Test
    void assign_allowedForOpsSupervisor() {
        authenticateAs("OPS_SUPERVISOR");
        UUID unitId = UUID.randomUUID();
        Map<String, Object> body = Map.of("assigneeType", "STAFF", "assigneeId", UUID.randomUUID().toString());
        when(client.assign(eq(unitId), any())).thenReturn(Map.of("active", true));

        assertThat(controller.assign(unitId, body)).containsEntry("active", true);
    }

    @Test
    void assign_forbiddenForExecutive() {
        authenticateAs("EXECUTIVE");
        UUID unitId = UUID.randomUUID();

        assertThatThrownBy(() -> controller.assign(unitId, Map.of()))
                .isInstanceOf(ResponseStatusException.class);
        verify(client, never()).assign(any(), any());
    }

    @Test
    void end_forbiddenWithoutAuthentication() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> controller.end("STAFF", id))
                .isInstanceOf(ResponseStatusException.class);
        verify(client, never()).endAssignment(any(), any());
    }

    @Test
    void end_adminWithActive_returns204() {
        authenticateAs("ADMIN");
        UUID id = UUID.randomUUID();
        when(client.endAssignment("STAFF", id)).thenReturn(true);

        assertThat(controller.end("STAFF", id).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }
}
