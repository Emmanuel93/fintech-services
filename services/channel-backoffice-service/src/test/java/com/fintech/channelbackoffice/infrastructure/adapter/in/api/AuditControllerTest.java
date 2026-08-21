package com.fintech.channelbackoffice.infrastructure.adapter.in.api;

import com.fintech.channelbackoffice.application.AuditSubjectResolver;
import com.fintech.channelbackoffice.application.PermissionsService;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.AuditClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.IdentityClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditControllerTest {

    private final AuditClient client = mock(AuditClient.class);
    private final AuditSubjectResolver subjectResolver = mock(AuditSubjectResolver.class);
    // El permiso va real: la regla que se comprueba aquí es «sólo con capacidad audit.view», y
    // simularla dejaría las tres pruebas de autorización afirmando lo que el mock diga.
    private final AuditController controller = new AuditController(
            client, subjectResolver, new PermissionsService(mock(IdentityClient.class)));

    /** El request no se usa salvo para reenviar el token al resolver por sujeto. */
    private static MockHttpServletRequest http() {
        return new MockHttpServletRequest();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "staff-1", "n/a", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    @Test
    void entries_allowedForAuditor_filtersByActor() {
        authenticateAs("AUDITOR");
        when(client.listEntries(any(), any(), any(), eq("juanp"), any(), any(), anyInt()))
                .thenReturn(List.of(Map.of("eventType", "IDENTITY_LOGIN_SUCCESS", "actor", "juanp")));

        List<Map<String, Object>> out = controller.entries(null, null, null, "juanp", null, null, null, 200, http());

        assertThat(out).hasSize(1);
        // El canal manda el límite explícito: la consola decide cuántas filas pide, no el default ajeno.
        verify(client).listEntries(null, null, null, "juanp", null, null, 200);
    }

    @Test
    void entries_allowedForAdmin() {
        authenticateAs("ADMIN");
        when(client.listEntries(any(), any(), any(), any(), any(), any())).thenReturn(List.of());

        assertThat(controller.entries(null, null, null, null, null, null, null, 200, http())).isEmpty();
    }

    @Test
    void entries_forbiddenForNonAuditRole() {
        authenticateAs("CREDIT_ANALYST");

        assertThatThrownBy(() -> controller.entries(null, null, null, null, null, null, null, 200, http()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
        verify(client, never()).listEntries(any(), any(), any(), any(), any(), any());
    }

    @Test
    void entries_forbiddenWithoutAuthentication() {
        assertThatThrownBy(() -> controller.entries(null, null, null, null, null, null, null, 200, http()))
                .isInstanceOf(ResponseStatusException.class);
    }
}
