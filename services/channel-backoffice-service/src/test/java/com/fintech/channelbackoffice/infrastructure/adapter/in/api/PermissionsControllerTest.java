package com.fintech.channelbackoffice.infrastructure.adapter.in.api;

import com.fintech.channelbackoffice.application.PermissionsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.IdentityClient;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PermissionsControllerTest {

    private final PermissionsController controller = new PermissionsController(
            new PermissionsService(mock(IdentityClient.class)), mock(IdentityClient.class));

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateAs(String... roles) {
        var authorities = List.of(roles).stream()
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("staff-1", "n/a", authorities));
    }

    @Test
    @SuppressWarnings("unchecked")
    void me_reflectsCallerRolesAndCapabilities() {
        authenticateAs("CREDIT_ANALYST");

        Map<String, Object> body = controller.me();

        assertThat((Set<String>) body.get("roles")).containsExactly("CREDIT_ANALYST");
        assertThat((Set<String>) body.get("capabilities")).contains("applications.analyze");
        assertThat((Set<String>) body.get("capabilities")).doesNotContain("applications.decide");
    }

    @Test
    void matrix_allowedForAdmin() {
        authenticateAs("ADMIN");

        Map<String, Set<String>> matrix = controller.matrix();

        assertThat(matrix).containsKey("UNDERWRITER");
    }

    @Test
    void matrix_forbiddenForNonAdmin() {
        authenticateAs("CREDIT_ANALYST");

        assertThatThrownBy(controller::matrix)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }
}
