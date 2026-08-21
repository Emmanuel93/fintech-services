package com.fintech.audit;

import com.fintech.audit.application.AccessAuditCommand;
import com.fintech.audit.application.service.AuditService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La migración 009 contra un Postgres real: que las columnas de identidad existan, que el mapeo
 * JPA las alcance y que lo escrito se lea igual.
 *
 * <p>Va por Testcontainers y no por H2 porque lo que se está verificando es precisamente el
 * cambio de esquema —columnas nuevas, índices parciales, el CHECK del formato—, y un motor
 * distinto al de producción no prueba nada de eso.
 */
@SpringBootTest
@Testcontainers
@DirtiesContext
class FullIdentityPersistenceIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fintech")
            .withUsername("fintech")
            .withPassword("fintech");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired AuditService auditService;

    private static AccessAuditCommand acceso(String partyId) {
        return new AccessAuditCommand(
                "VIEW",
                "77777777-8888-4999-8aaa-bbbbbbbbbbbb",
                "ana.torres@kredius.mx", "Ana Torres", "TOAA900101HDFRNN09", null,
                "EXECUTIVE", "BACKOFFICE",
                partyId, "Pedro Ramírez Soto", "RASP880920HDFMTD05",
                "pedro.ramirez@example.mx", "5215512345678",
                "189.203.10.5", "Mozilla/5.0", "sesion-1",
                "clients", partyId,
                "GET", "/clients/" + partyId, null,
                "SUCCESS", 200, 12L, "corr-1234",
                "channel-backoffice-service", Instant.parse("2026-08-13T15:30:00Z"));
    }

    @Test
    void persiste_yReleeLaIdentidadCompletaDeQuienActuaYDeQuienEsActuado() {
        String partyId = UUID.randomUUID().toString();

        UUID entryId = auditService.recordAccess(acceso(partyId)).getEntryId();
        var leida = auditService.findById(entryId);

        // Quién actuó, identificado plenamente
        assertThat(leida.getActorName()).isEqualTo("Ana Torres");
        assertThat(leida.getActorEmail()).isEqualTo("ana.torres@kredius.mx");
        assertThat(leida.getActorCurp()).isEqualTo("TOAA900101HDFRNN09");

        // Sobre quién, con su id en la columna que ya existía
        assertThat(leida.getPartyId()).isEqualTo(partyId);
        assertThat(leida.getSubjectName()).isEqualTo("Pedro Ramírez Soto");
        assertThat(leida.getSubjectCurp()).isEqualTo("RASP880920HDFMTD05");
        assertThat(leida.getSubjectEmail()).isEqualTo("pedro.ramirez@example.mx");
        assertThat(leida.getSubjectPhone()).isEqualTo("5215512345678");

        // Y los tres defectos colaterales
        assertThat(leida.getCorrelationId()).isEqualTo("corr-1234");
        assertThat(leida.getDomainSource()).isEqualTo("channel-backoffice-service");
        assertThat(leida.getResourceId()).isEqualTo(partyId);
    }

    @Test
    void unAccesoMovil_seAtribuyeAlCanalMovil() {
        var movil = new AccessAuditCommand(
                "VIEW",
                "33333333-4444-4555-8666-777777777777",
                "pedro.ramirez@example.mx", "Pedro Ramírez Soto", "RASP880920HDFMTD05",
                "5215512345678", "CUSTOMER", "MOBILE",
                null, null, null, null, null,
                "10.0.0.7", "KrediusApp/2.1", "sesion-9",
                "wallet", null,
                "GET", "/wallet/balance", null,
                "SUCCESS", 200, 8L, "corr-móvil",
                "channel-mobile-service", Instant.now());

        var leida = auditService.findById(auditService.recordAccess(movil).getEntryId());

        assertThat(leida.getDomainSource()).isEqualTo("channel-mobile-service");
        assertThat(leida.getActorChannel()).isEqualTo("MOBILE");
        assertThat(leida.getActorPhone()).isEqualTo("5215512345678");
        assertThat(leida.getActorCurp()).isEqualTo("RASP880920HDFMTD05");
    }

    @Test
    void unHechoDeDominio_sigueSinIdentidadYNoSeRompe() {
        var entry = auditService.record("PROSPECT_CREATED", "origination-service",
                UUID.randomUUID().toString(), null, "corr-x", "{}");

        var leida = auditService.findById(entry.getEntryId());

        assertThat(leida.getCategory()).isEqualTo("DOMAIN_EVENT");
        assertThat(leida.getActorCurp()).isNull();
        assertThat(leida.getSubjectName()).isNull();
    }
}
