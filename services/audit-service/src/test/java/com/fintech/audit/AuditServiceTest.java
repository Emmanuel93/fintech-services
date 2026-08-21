package com.fintech.audit;

import com.fintech.audit.application.AccessAuditCommand;
import com.fintech.audit.application.port.out.AuditEntryRepository;
import com.fintech.audit.application.service.AuditService;
import com.fintech.audit.domain.AuditEntry;
import com.fintech.audit.domain.AuditEntryNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @Mock AuditEntryRepository repository;

    AuditService service;

    @BeforeEach
    void setUp() {
        service = new AuditService(repository);
    }

    @Test
    void record_savesEntry() {
        AuditEntry saved = AuditEntry.create("TEST_EVENT", "test-service", "agg-1", null, null, "{}");
        when(repository.save(any())).thenReturn(saved);

        AuditEntry result = service.record("TEST_EVENT", "test-service", "agg-1", null, null, "{}");

        assertThat(result.getEventType()).isEqualTo("TEST_EVENT");
        assertThat(result.getDomainSource()).isEqualTo("test-service");
        verify(repository).save(any(AuditEntry.class));
    }

    @Test
    void record_withActor_persistsActor_andSanitizesPayload() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);

        service.record("IDENTITY_LOGIN_SUCCESS", "identity-service", "party-1", "party-1", null,
                "juanp", "{\"username\":\"juanp\",\"password\":\"s3cr3t\"}");

        verify(repository).save(captor.capture());
        AuditEntry saved = captor.getValue();
        assertThat(saved.getActor()).isEqualTo("juanp");
        // El secreto se redacta; el username (no secreto) se conserva.
        assertThat(saved.getPayload()).doesNotContain("s3cr3t").contains("***REDACTED***").contains("juanp");
    }

    @Test
    void recordAccess_persistsWhoWhatWhen_andSanitizesQuery() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        Instant when = Instant.parse("2026-08-12T15:30:00Z");

        var cmd = new AccessAuditCommand(
                "DOWNLOAD",
                "staff-77", "maria.lopez@fintech.mx", "María López",
                "LOMA850315MDFPRR07", null, "AUDITOR,ADMIN", "BACKOFFICE",
                "9f1c0d22-5b3e-4a71-8c44-2b6f9e0a1d33", "Pedro Ramírez Soto",
                "RASP880920HDFMTD05", "pedro.ramirez@example.mx", "5215512345678",
                "189.203.10.5", "Mozilla/5.0", "corr-abc",
                "portfolio", "3f2504e0-4f89-41d3-9a0c-0305e82c3301",
                "GET", "/portfolio/3f2504e0-4f89-41d3-9a0c-0305e82c3301/export",
                "token=s3cr3t&format=csv",
                "SUCCESS", 200, 42L, "corr-abc", "channel-backoffice-service", when);

        service.recordAccess(cmd);

        verify(repository).save(captor.capture());
        AuditEntry e = captor.getValue();
        // Quién — legible: correo y nombre, no sólo el UUID
        assertThat(e.getCategory()).isEqualTo(AuditEntry.CATEGORY_ACCESS);
        assertThat(e.getActor()).isEqualTo("staff-77");
        assertThat(e.getActorEmail()).isEqualTo("maria.lopez@fintech.mx");
        assertThat(e.getActorName()).isEqualTo("María López");
        assertThat(e.getActorRoles()).isEqualTo("AUDITOR,ADMIN");
        assertThat(e.getActorChannel()).isEqualTo("BACKOFFICE");
        assertThat(e.getActorIp()).isEqualTo("189.203.10.5");
        assertThat(e.getUserAgent()).isEqualTo("Mozilla/5.0");
        // Identidad plena de quien actúa: la CURP va junto al nombre y al correo.
        assertThat(e.getActorCurp()).isEqualTo("LOMA850315MDFPRR07");
        // Sobre quién — la otra mitad de la pregunta, con su id en party_id.
        assertThat(e.getPartyId()).isEqualTo("9f1c0d22-5b3e-4a71-8c44-2b6f9e0a1d33");
        assertThat(e.getSubjectName()).isEqualTo("Pedro Ramírez Soto");
        assertThat(e.getSubjectCurp()).isEqualTo("RASP880920HDFMTD05");
        assertThat(e.getSubjectEmail()).isEqualTo("pedro.ramirez@example.mx");
        assertThat(e.getSubjectPhone()).isEqualTo("5215512345678");
        // El canal que reportó, no un valor fijo.
        assertThat(e.getDomainSource()).isEqualTo("channel-backoffice-service");
        // Sobre qué
        assertThat(e.getAction()).isEqualTo("DOWNLOAD");
        assertThat(e.getEventType()).isEqualTo("ACCESS_DOWNLOAD");
        assertThat(e.getResourceType()).isEqualTo("portfolio");
        assertThat(e.getResourceId()).isEqualTo("3f2504e0-4f89-41d3-9a0c-0305e82c3301");
        assertThat(e.getHttpMethod()).isEqualTo("GET");
        assertThat(e.getStatusCode()).isEqualTo(200);
        assertThat(e.getOutcome()).isEqualTo("SUCCESS");
        assertThat(e.getDurationMs()).isEqualTo(42L);
        // El secreto que viajó por la query se redacta antes de persistir.
        assertThat(e.getHttpQuery()).doesNotContain("s3cr3t").contains("format=csv");
        // Cuándo
        assertThat(e.getOccurredAt()).isEqualTo(when);
        assertThat(e.getCreatedAt()).isNotNull();
    }

    @Test
    void findByActorIp_andAction_andCategory_delegate() {
        when(repository.findByActorIp(any(), any(), any(), anyInt())).thenReturn(List.of());
        when(repository.findByAction(any(), any(), any(), anyInt())).thenReturn(List.of());
        when(repository.findByCategory(any(), any(), any(), anyInt())).thenReturn(List.of());

        service.findByActorIp("189.203.10.5", Instant.EPOCH, Instant.now(), 200);
        service.findByAction("DOWNLOAD", Instant.EPOCH, Instant.now(), 200);
        service.findByCategory("ACCESS", Instant.EPOCH, Instant.now(), 200);

        verify(repository).findByActorIp(eq("189.203.10.5"), any(), any(), anyInt());
        verify(repository).findByAction(eq("DOWNLOAD"), any(), any(), anyInt());
        verify(repository).findByCategory(eq("ACCESS"), any(), any(), anyInt());
    }

    @Test
    void findById_returnsEntry() {
        UUID id = UUID.randomUUID();
        AuditEntry entry = AuditEntry.create("EV", "src", "agg", null, null, "{}");
        when(repository.findById(id)).thenReturn(Optional.of(entry));

        AuditEntry result = service.findById(id);
        assertThat(result).isEqualTo(entry);
    }

    @Test
    void findById_throwsWhenNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(id))
                .isInstanceOf(AuditEntryNotFoundException.class);
    }

    @Test
    void findByPartyId_delegatesToRepository() {
        when(repository.findByPartyId(any(), any(), any(), anyInt())).thenReturn(List.of());

        List<AuditEntry> result = service.findByPartyId("party-1", Instant.EPOCH, Instant.now(), 200);

        assertThat(result).isEmpty();
        verify(repository).findByPartyId(eq("party-1"), any(), any(), anyInt());
    }

    @Test
    void findByAggregateId_delegatesToRepository() {
        when(repository.findByAggregateId("agg-123", 200)).thenReturn(List.of());

        service.findByAggregateId("agg-123", 200);

        verify(repository).findByAggregateId("agg-123", 200);
    }

    // ── El límite: lo que evita que una consulta devuelva la bitácora completa ───────────────

    @Test
    void limitAusenteOCeroCaeAlDefault_nuncaATodo() {
        when(repository.findByDateRange(any(), any(), anyInt())).thenReturn(List.of());

        service.findByDateRange(Instant.EPOCH, Instant.now(), 0);
        service.findByDateRange(Instant.EPOCH, Instant.now(), -1);

        // Ningún camino puede terminar en «sin límite»: el default es un número, no la ausencia
        // de uno. Esta consulta sin filtros es exactamente la que reventó al backoffice con
        // DataBufferLimitException.
        verify(repository, times(2)).findByDateRange(any(), any(), eq(AuditService.DEFAULT_LIMIT));
    }

    @Test
    void limitDesmedidoSeAcotaAlTope() {
        when(repository.findByEventType(any(), any(), any(), anyInt())).thenReturn(List.of());

        service.findByEventType("X", Instant.EPOCH, Instant.now(), 999_999);

        verify(repository).findByEventType(eq("X"), any(), any(), eq(AuditService.MAX_LIMIT));
    }

    @Test
    void limitRazonableSeRespeta() {
        when(repository.findByActor(any(), any(), any(), anyInt())).thenReturn(List.of());

        service.findByActor("staff-1", Instant.EPOCH, Instant.now(), 50);

        verify(repository).findByActor(eq("staff-1"), any(), any(), eq(50));
    }

    @Test
    void todaConsultaDeListadoQuedaAcotada() {
        // Guarda de diseño: si mañana alguien agrega un findBy… al puerto sin `limit`, este test
        // falla al compilar. Es a propósito — la cota no es opcional en este servicio.
        when(repository.findByPartyId(any(), any(), any(), anyInt())).thenReturn(List.of());
        when(repository.findByAggregateId(any(), anyInt())).thenReturn(List.of());
        when(repository.findByEventType(any(), any(), any(), anyInt())).thenReturn(List.of());
        when(repository.findByActor(any(), any(), any(), anyInt())).thenReturn(List.of());
        when(repository.findByActorIp(any(), any(), any(), anyInt())).thenReturn(List.of());
        when(repository.findByAction(any(), any(), any(), anyInt())).thenReturn(List.of());
        when(repository.findByCategory(any(), any(), any(), anyInt())).thenReturn(List.of());
        when(repository.findByDateRange(any(), any(), anyInt())).thenReturn(List.of());

        Instant from = Instant.EPOCH, to = Instant.now();
        assertThat(service.findByPartyId("p", from, to, 0)).isEmpty();
        assertThat(service.findByAggregateId("a", 0)).isEmpty();
        assertThat(service.findByEventType("e", from, to, 0)).isEmpty();
        assertThat(service.findByActor("ac", from, to, 0)).isEmpty();
        assertThat(service.findByActorIp("ip", from, to, 0)).isEmpty();
        assertThat(service.findByAction("act", from, to, 0)).isEmpty();
        assertThat(service.findByCategory("c", from, to, 0)).isEmpty();
        assertThat(service.findByDateRange(from, to, 0)).isEmpty();
    }
}
