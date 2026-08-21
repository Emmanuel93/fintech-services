package com.fintech.scoring.infrastructure.adapter.in.messaging;

import com.fintech.scoring.application.service.BureauPrefetchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class ProspectCreatedEventListenerTest {

    @Mock BureauPrefetchService prefetchService;

    ProspectCreatedEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new ProspectCreatedEventListener(prefetchService);
    }

    private ProspectCreatedPayload payloadWith(boolean circuloConsent) {
        return new ProspectCreatedPayload(
                UUID.randomUUID(),
                "INDIVIDUAL", "MOBILE_APP", "PERSONAL_LOAN",
                "JUAN", "SESENTAYDOS", "PRUEBA",
                "SEPJ650809HDFXXX01", "SEPJ650809JG1",
                LocalDate.of(1965, 8, 9),
                "PASADISO 58", "58", null,
                "MONTEVIDEO", "GUSTAVO A MADERO", "CIUDAD DE MEXICO",
                "CDMX", "07730",
                circuloConsent,
                "event-id-001");
    }

    @Test
    void onProspectCreated_consentGranted_callsPrefetchServiceWithAllArgs() {
        ProspectCreatedPayload payload = payloadWith(true);

        listener.onProspectCreated(payload);

        then(prefetchService).should(times(1)).initiate(
                eq(payload.prospectId()),
                eq("SEPJ650809HDFXXX01"),
                eq("event-id-001"),
                eq("JUAN"),
                eq("SESENTAYDOS"),
                eq("PRUEBA"),
                eq("SEPJ650809JG1"),
                eq(LocalDate.of(1965, 8, 9)),
                eq("PASADISO 58"),
                eq("58"),
                isNull(),
                eq("MONTEVIDEO"),
                eq("GUSTAVO A MADERO"),
                eq("CIUDAD DE MEXICO"),
                eq("CDMX"),
                eq("07730"),
                eq("INDIVIDUAL"),
                eq("PERSONAL_LOAN"));
    }

    @Test
    void onProspectCreated_consentNotGranted_skipsCirculoQuery() {
        listener.onProspectCreated(payloadWith(false));

        then(prefetchService).should(never()).initiate(
                any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any());
    }

    @Test
    void onProspectCreated_nullEventId_fallsBackToProspectId() {
        UUID prospectId = UUID.randomUUID();
        ProspectCreatedPayload payload = new ProspectCreatedPayload(
                prospectId,
                "INDIVIDUAL", "MOBILE_APP", null,
                "ANA", "LOPEZ", null,
                "LOAA900101MDFPNA00", null,
                LocalDate.of(1990, 1, 1),
                "Calle 1", "10", null,
                "Col", null, "Guadalajara", "JAL", "44100",
                true,
                null);

        listener.onProspectCreated(payload);

        then(prefetchService).should(times(1)).initiate(
                eq(prospectId),
                eq("LOAA900101MDFPNA00"),
                eq(prospectId.toString()),
                any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void onProspectCreated_prospectTypeAndProductTypePassedThrough() {
        ProspectCreatedPayload payload = new ProspectCreatedPayload(
                UUID.randomUUID(),
                "BUSINESS", "BRANCH", "REVOLVING_LINE",
                "EMPRESA", "SA", "DE CV",
                "EMSA900101HDFXXX01", "EMSA900101JG1",
                LocalDate.of(1990, 1, 1),
                "Av Reforma", "100", null,
                "Centro", "Cuauhtémoc", "CDMX", "CDMX", "06600",
                true,
                "event-biz-001");

        listener.onProspectCreated(payload);

        then(prefetchService).should(times(1)).initiate(
                any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(),
                any(), any(),
                eq("BUSINESS"),
                eq("REVOLVING_LINE"));
    }
}
