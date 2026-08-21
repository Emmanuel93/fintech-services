package com.fintech.channelbackoffice.infrastructure.adapter.in.api;

import com.fintech.channelbackoffice.infrastructure.adapter.out.client.AuditClient;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Lo que la bitácora del backoffice tiene que dejar escrito por cada request: quién actuó con
 * nombre y CURP, sobre quién, con qué correlación y desde qué canal.
 */
class AccessAuditInterceptorTest {

    private static final UUID STAFF = UUID.fromString("77777777-8888-4999-8aaa-bbbbbbbbbbbb");
    private static final UUID PARTY = UUID.fromString("9f1c0d22-5b3e-4a71-8c44-2b6f9e0a1d33");
    private static final UUID SOLICITUD = UUID.fromString("11111111-2222-4333-8444-555555555555");

    private final AuditClient auditClient = mock(AuditClient.class);
    private final StaffIdentityResolver staff = mock(StaffIdentityResolver.class);
    private final CustomerIdentityResolver customers = mock(CustomerIdentityResolver.class);
    private final AccessAuditInterceptor interceptor =
            new AccessAuditInterceptor(auditClient, staff, customers);

    @AfterEach
    void limpia() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    private void autenticaComoEjecutivo() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(STAFF.toString(), null,
                        List.of(new SimpleGrantedAuthority("ROLE_EXECUTIVE"))));
        when(staff.resolve(STAFF.toString())).thenReturn(Optional.of(
                new StaffIdentityResolver.StaffIdentity(
                        "ana.torres@kredius.mx", "Ana Torres", "TOAA900101HDFRNN09")));
    }

    private AuditClient.AccessLog registra(String method, String path) {
        var request = new MockHttpServletRequest(method, path);
        var response = new MockHttpServletResponse();
        response.setStatus(200);
        interceptor.preHandle(request, response, new Object());
        interceptor.afterCompletion(request, response, new Object(), null);

        var captor = ArgumentCaptor.forClass(AuditClient.AccessLog.class);
        verify(auditClient).logAccess(captor.capture(), any());
        return captor.getValue();
    }

    @Test
    void registra_nombreCurpYCorreo_deUnEjecutivoQueNoEsAdmin() {
        autenticaComoEjecutivo();

        var log = registra("GET", "/dashboard/summary");

        assertThat(log.actor()).isEqualTo(STAFF.toString());
        assertThat(log.actorName()).isEqualTo("Ana Torres");
        assertThat(log.actorCurp()).isEqualTo("TOAA900101HDFRNN09");
        assertThat(log.actorEmail()).isEqualTo("ana.torres@kredius.mx");
    }

    @Test
    void sobreLaRutaDeUnCliente_registraTambienAlSujeto() {
        autenticaComoEjecutivo();
        when(customers.resolve(PARTY.toString())).thenReturn(Optional.of(
                new CustomerIdentityResolver.CustomerIdentity(
                        PARTY, "Pedro Ramírez Soto", "RASP880920HDFMTD05",
                        "pedro.ramirez@example.mx", "5215512345678")));

        var log = registra("GET", "/clients/" + PARTY);

        assertThat(log.subjectPartyId()).isEqualTo(PARTY.toString());
        assertThat(log.subjectName()).isEqualTo("Pedro Ramírez Soto");
        assertThat(log.subjectCurp()).isEqualTo("RASP880920HDFMTD05");
        assertThat(log.subjectEmail()).isEqualTo("pedro.ramirez@example.mx");
        assertThat(log.subjectPhone()).isEqualTo("5215512345678");
    }

    @Test
    void sobreUnUuidQueNoEsUnCliente_niSiquieraPreguntaPorElSujeto() {
        autenticaComoEjecutivo();

        var log = registra("GET", "/origination/applications/" + SOLICITUD);

        assertThat(log.resourceId()).isEqualTo(SOLICITUD.toString());
        assertThat(log.subjectPartyId()).isNull();
        verify(customers, never()).resolve(any());
    }

    @Test
    void tomaLaCorrelacionDelMdc_noDeLaPeticion() {
        autenticaComoEjecutivo();
        MDC.put(MdcCorrelationFilter.MDC_KEY, "corr-abcd");

        // El filtro genera el valor cuando el navegador no lo manda y lo deja en el MDC y en la
        // respuesta. Leerlo de la petición dejaba la columna vacía en el 100% de las entradas.
        assertThat(registra("GET", "/dashboard/summary").correlationId()).isEqualTo("corr-abcd");
    }

    @Test
    void seAtribuyeAlBackoffice_conNombreDeServicioExplicito() {
        autenticaComoEjecutivo();

        assertThat(registra("GET", "/dashboard/summary").domainSource())
                .isEqualTo("channel-backoffice-service");
    }

    @Test
    void identityCaido_registraElHechoConElUuidPelado() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(STAFF.toString(), null,
                        List.of(new SimpleGrantedAuthority("ROLE_EXECUTIVE"))));
        when(staff.resolve(any())).thenReturn(Optional.empty());

        var log = registra("GET", "/dashboard/summary");

        assertThat(log.actor()).isEqualTo(STAFF.toString());
        assertThat(log.actorName()).isNull();
        assertThat(log.actorCurp()).isNull();
        assertThat(log.action()).isEqualTo("VIEW");
        assertThat(log.outcome()).isEqualTo("SUCCESS");
    }

    @Test
    void sinIdentidad_noRegistraNada_esTraficoDeInfra() {
        interceptor.afterCompletion(new MockHttpServletRequest("GET", "/actuator/health"),
                new MockHttpServletResponse(), new Object(), null);

        verify(auditClient, never()).logAccess(any(), any());
    }
}
