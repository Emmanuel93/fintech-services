package com.fintech.channelmobile.infrastructure.adapter.in.api;

import com.fintech.channelmobile.infrastructure.adapter.out.client.MobileAuditClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Lo que este canal registraba del cliente era: su UUID. Nombre, CURP, correo y teléfono iban
 * {@code null} literal, el id del recurso también, la correlación se leía del lado equivocado y
 * todo quedaba atribuido al backoffice. Esta prueba fija las cuatro cosas.
 */
class MobileAccessAuditInterceptorTest {

    private static final UUID PROSPECT = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3301");
    private static final UUID PARTY    = UUID.fromString("9f1c0d22-5b3e-4a71-8c44-2b6f9e0a1d33");
    private static final UUID CUENTA   = UUID.fromString("11111111-2222-4333-8444-555555555555");

    private final MobileAuditClient auditClient = mock(MobileAuditClient.class);
    private final CustomerIdentityResolver resolver = mock(CustomerIdentityResolver.class);
    private final MobileAccessAuditInterceptor interceptor =
            new MobileAccessAuditInterceptor(auditClient, resolver);

    @AfterEach
    void limpia() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    private void autenticaComoCliente() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(PROSPECT.toString(), null,
                        List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))));
        when(resolver.resolve(PROSPECT.toString())).thenReturn(Optional.of(
                new CustomerIdentityResolver.CustomerIdentity(
                        PARTY, "Pedro Ramírez Soto", "RASP880920HDFMTD05",
                        "pedro.ramirez@example.mx", "5215512345678")));
    }

    private MobileAuditClient.AccessLog registra(MockHttpServletRequest request) {
        var response = new MockHttpServletResponse();
        response.setStatus(200);
        interceptor.preHandle(request, response, new Object());
        interceptor.afterCompletion(request, response, new Object(), null);

        var captor = ArgumentCaptor.forClass(MobileAuditClient.AccessLog.class);
        verify(auditClient).logAccess(captor.capture(), any());
        return captor.getValue();
    }

    @Test
    void registra_laIdentidadCompletaDelCliente() {
        autenticaComoCliente();
        var request = new MockHttpServletRequest("GET", "/credit/accounts/" + CUENTA);
        MDC.put(MdcCorrelationFilter.MDC_KEY, "corr-1234");

        var log = registra(request);

        assertThat(log.actorName()).isEqualTo("Pedro Ramírez Soto");
        assertThat(log.actorCurp()).isEqualTo("RASP880920HDFMTD05");
        assertThat(log.actorEmail()).isEqualTo("pedro.ramirez@example.mx");
        assertThat(log.actorPhone()).isEqualTo("5215512345678");
    }

    @Test
    void enLaApp_elActorYElSujetoSonLaMismaPersona() {
        autenticaComoCliente();
        var log = registra(new MockHttpServletRequest("GET", "/wallet/balance"));

        assertThat(log.subjectPartyId()).isEqualTo(PARTY.toString());
        assertThat(log.subjectName()).isEqualTo("Pedro Ramírez Soto");
        assertThat(log.subjectCurp()).isEqualTo("RASP880920HDFMTD05");
        assertThat(log.subjectPhone()).isEqualTo("5215512345678");
    }

    @Test
    void extrae_elIdDeLaRuta_queAntesIbaSiempreNulo() {
        autenticaComoCliente();
        var log = registra(new MockHttpServletRequest("GET", "/credit/accounts/" + CUENTA));

        assertThat(log.resourceId()).isEqualTo(CUENTA.toString());
    }

    @Test
    void tomaLaCorrelacionDelMdc_noDeLaPeticion() {
        autenticaComoCliente();
        MDC.put(MdcCorrelationFilter.MDC_KEY, "corr-1234");

        // El cliente no manda la cabecera —nunca lo hace—; el filtro generó el valor y lo dejó
        // en el MDC. Leer la petición devolvía null en el 100% de las entradas.
        var log = registra(new MockHttpServletRequest("GET", "/wallet/balance"));

        assertThat(log.correlationId()).isEqualTo("corr-1234");
    }

    @Test
    void seAtribuyeAlCanalMovil_noAlBackoffice() {
        autenticaComoCliente();
        var log = registra(new MockHttpServletRequest("GET", "/wallet/balance"));

        assertThat(log.domainSource()).isEqualTo("channel-mobile-service");
        assertThat(log.actorChannel()).isEqualTo("MOBILE");
    }

    @Test
    void siNoSeResuelveLaIdentidad_seRegistraElHechoConElUuidPelado() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(PROSPECT.toString(), null, List.of()));
        when(resolver.resolve(any())).thenReturn(Optional.empty());

        var log = registra(new MockHttpServletRequest("GET", "/wallet/balance"));

        assertThat(log.actor()).isEqualTo(PROSPECT.toString());
        assertThat(log.actorName()).isNull();
        assertThat(log.domainSource()).isEqualTo("channel-mobile-service");
    }

    @Test
    void recursoId_soloCuandoLaRutaTraeUnUuid() {
        assertThat(MobileAccessAuditInterceptor.recursoId("/credit/accounts/" + CUENTA))
                .isEqualTo(CUENTA.toString());
        assertThat(MobileAccessAuditInterceptor.recursoId("/wallet/balance")).isNull();
        assertThat(MobileAccessAuditInterceptor.recursoId(null)).isNull();
    }
}
