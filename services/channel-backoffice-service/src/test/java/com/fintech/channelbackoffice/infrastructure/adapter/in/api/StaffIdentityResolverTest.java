package com.fintech.channelbackoffice.infrastructure.adapter.in.api;

import com.fintech.channelbackoffice.infrastructure.adapter.out.client.IdentityClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.ServiceTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * El resolutor traduce el UUID del empleado a su identidad completa, y sobre todo <b>no estorba</b>:
 * cada camino de fallo tiene que acabar en «se pierde el nombre, nunca el hecho».
 */
class StaffIdentityResolverTest {

    private static final UUID STAFF = UUID.fromString("11111111-2222-4333-8444-555555555555");
    private static final String CURP = "TOAA900101HDFRNN09";

    private final IdentityClient identity = mock(IdentityClient.class);
    private final ServiceTokenProvider tokens = mock(ServiceTokenProvider.class);
    private final StaffIdentityResolver resolver = new StaffIdentityResolver(identity, tokens);

    private static IdentityClient.StaffUserResponse staff() {
        return new IdentityClient.StaffUserResponse(
                STAFF, "ana.torres@kredius.mx", "Ana Torres", CURP, "INTERNO", null,
                List.of("EXECUTIVE"), "ACTIVE", Instant.now(), Instant.now());
    }

    @Test
    void resuelve_nombreCurpYCorreo_conLaCredencialDelCanal() {
        when(tokens.token()).thenReturn(Optional.of("token-de-servicio"));
        when(identity.getStaff("token-de-servicio", STAFF)).thenReturn(staff());

        var quien = resolver.resolve(STAFF.toString()).orElseThrow();

        assertThat(quien.fullName()).isEqualTo("Ana Torres");
        assertThat(quien.email()).isEqualTo("ana.torres@kredius.mx");
        assertThat(quien.curp()).isEqualTo(CURP);
    }

    @Test
    void cachea_yNoVuelveAPreguntarPorElMismoEmpleado() {
        when(tokens.token()).thenReturn(Optional.of("t"));
        when(identity.getStaff(any(), eq(STAFF))).thenReturn(staff());

        resolver.resolve(STAFF.toString());
        resolver.resolve(STAFF.toString());
        resolver.resolve(STAFF.toString());

        verify(identity, times(1)).getStaff(any(), eq(STAFF));
    }

    @Test
    void sinCredencialDeServicio_noPreguntaYDegradaAlUuid() {
        when(tokens.token()).thenReturn(Optional.empty());

        assertThat(resolver.resolve(STAFF.toString())).isEmpty();
        verify(identity, never()).getStaff(any(), any());
    }

    @Test
    void identityCaido_noLanzaYDegradaAlUuid() {
        when(tokens.token()).thenReturn(Optional.of("t"));
        when(identity.getStaff(any(), eq(STAFF)))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "identity caído"));

        assertThat(resolver.resolve(STAFF.toString())).isEmpty();
    }

    @Test
    void trasUnFallo_cacheaNegativoYNoInsiste() {
        when(tokens.token()).thenReturn(Optional.of("t"));
        when(identity.getStaff(any(), eq(STAFF)))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "no existe"));

        resolver.resolve(STAFF.toString());
        resolver.resolve(STAFF.toString());

        verify(identity, times(1)).getStaff(any(), eq(STAFF));
    }

    @Test
    void tokenRechazado_loRenuevaYReintentaUnaVez() {
        when(tokens.token()).thenReturn(Optional.of("viejo"), Optional.of("nuevo"));
        when(identity.getStaff("viejo", STAFF))
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "token muerto"));
        when(identity.getStaff("nuevo", STAFF)).thenReturn(staff());

        var quien = resolver.resolve(STAFF.toString()).orElseThrow();

        assertThat(quien.fullName()).isEqualTo("Ana Torres");
        verify(tokens).invalidate();
    }

    @Test
    void tokenRechazadoDosVeces_seRindeSinLanzar() {
        when(tokens.token()).thenReturn(Optional.of("t"));
        when(identity.getStaff(any(), eq(STAFF)))
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "sigue mal"));

        assertThat(resolver.resolve(STAFF.toString())).isEmpty();
        verify(identity, times(2)).getStaff(any(), eq(STAFF));
    }

    @Test
    void actorQueNoEsUuid_niSiquieraPregunta() {
        assertThat(resolver.resolve("SYSTEM")).isEmpty();
        assertThat(resolver.resolve(null)).isEmpty();
        assertThat(resolver.resolve("  ")).isEmpty();
        verify(tokens, never()).token();
    }
}
