package com.fintech.channelbackoffice.infrastructure.adapter.in.api;

import com.fintech.channelbackoffice.infrastructure.adapter.out.client.OriginationClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.PartyClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * El sujeto sobre el que se actúa. Su identidad vive repartida en dos servicios, así que lo que hay
 * que comprobar es que la mitad que sí conteste llegue a la bitácora aunque la otra falle.
 */
class CustomerIdentityResolverTest {

    private static final UUID PARTY    = UUID.fromString("9f1c0d22-5b3e-4a71-8c44-2b6f9e0a1d33");
    private static final UUID PROSPECT = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3301");
    private static final String CURP   = "RASP880920HDFMTD05";

    private final PartyClient party = mock(PartyClient.class);
    private final OriginationClient origination = mock(OriginationClient.class);
    private final CustomerIdentityResolver resolver = new CustomerIdentityResolver(party, origination);

    private static PartyClient.PartyResponse party() {
        return new PartyClient.PartyResponse(
                PARTY, PROSPECT, null, "INDIVIDUAL", "ACTIVE",
                "Pedro", "Ramírez", "Soto", CURP, "RASP880920AB1",
                null, "LOW", 700, null, null, Instant.now());
    }

    private static OriginationClient.ProspectDetailResponse prospecto() {
        return new OriginationClient.ProspectDetailResponse(
                PROSPECT.toString(), "INDIVIDUAL", "ACTIVE",
                "Pedro", "Ramírez", "Soto", CURP, "RASP880920AB1",
                null, "H", "DF", "5215512345678", "pedro.ramirez@example.mx",
                null, "MOBILE", true, Instant.now(), true, Instant.now(),
                List.of(), Instant.now(), Instant.now());
    }

    @Test
    void resuelve_nombreCurpCorreoYTelefono_encadenandoPartyYOrigination() {
        when(party.getByPartyId(PARTY)).thenReturn(party());
        when(origination.getProspect(PROSPECT)).thenReturn(prospecto());

        var sujeto = resolver.resolve(PARTY.toString()).orElseThrow();

        assertThat(sujeto.partyId()).isEqualTo(PARTY);
        assertThat(sujeto.fullName()).isEqualTo("Pedro Ramírez Soto");
        assertThat(sujeto.curp()).isEqualTo(CURP);
        assertThat(sujeto.email()).isEqualTo("pedro.ramirez@example.mx");
        assertThat(sujeto.phone()).isEqualTo("5215512345678");
    }

    @Test
    void originationCaido_conservaNombreYCurp_ySoloPierdeElContacto() {
        when(party.getByPartyId(PARTY)).thenReturn(party());
        when(origination.getProspect(PROSPECT))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "origination caído"));

        var sujeto = resolver.resolve(PARTY.toString()).orElseThrow();

        assertThat(sujeto.fullName()).isEqualTo("Pedro Ramírez Soto");
        assertThat(sujeto.curp()).isEqualTo(CURP);
        assertThat(sujeto.email()).isNull();
        assertThat(sujeto.phone()).isNull();
    }

    @Test
    void idQueNoEsUnCliente_noLanzaYNoVuelveAPreguntar() {
        when(party.getByPartyId(any()))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "no es un party"));

        assertThat(resolver.resolve(PARTY.toString())).isEmpty();
        assertThat(resolver.resolve(PARTY.toString())).isEmpty();

        verify(party, times(1)).getByPartyId(any());
        verify(origination, never()).getProspect(any());
    }

    @Test
    void clienteSinProspecto_seResuelveSinContactoYSinLlamarAOrigination() {
        when(party.getByPartyId(PARTY)).thenReturn(new PartyClient.PartyResponse(
                PARTY, null, null, "INDIVIDUAL", "ACTIVE",
                "Pedro", "Ramírez", null, CURP, null,
                null, null, null, null, null, Instant.now()));

        var sujeto = resolver.resolve(PARTY.toString()).orElseThrow();

        assertThat(sujeto.fullName()).isEqualTo("Pedro Ramírez");
        assertThat(sujeto.email()).isNull();
        verify(origination, never()).getProspect(any());
    }

    @Test
    void cachea_yNoRepiteLaCadenaPorCadaPantallaDelExpediente() {
        when(party.getByPartyId(PARTY)).thenReturn(party());
        when(origination.getProspect(PROSPECT)).thenReturn(prospecto());

        resolver.resolve(PARTY.toString());
        resolver.resolve(PARTY.toString());
        resolver.resolve(PARTY.toString());

        verify(party, times(1)).getByPartyId(PARTY);
        verify(origination, times(1)).getProspect(PROSPECT);
    }

    @Test
    void idQueNoEsUuid_niSiquieraPregunta() {
        assertThat(resolver.resolve("dashboard")).isEmpty();
        assertThat(resolver.resolve(null)).isEmpty();
        verify(party, never()).getByPartyId(any());
    }
}
