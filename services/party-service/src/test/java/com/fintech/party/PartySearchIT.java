package com.fintech.party;

import com.fintech.party.application.port.out.PartyRepository;
import com.fintech.party.domain.Party;
import com.fintech.party.domain.PartyStatus;
import com.fintech.party.domain.PartyType;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Modo consulta de party contra Postgres real: la búsqueda por nombre/CURP/RFC
 * (índice pg_trgm de la migración 008), los filtros por tipo/estado, la
 * paginación real y la hidratación por lote.
 *
 * <p>El test de performance sostiene el invariante rector: el {@code /batch}
 * resuelve N parties en <b>una</b> sentencia, sin importar cuántos — si el nº de
 * consultas dependiera del nº de filas, el listado del backoffice sería un N+1.
 */
@SpringBootTest
@Testcontainers
@EmbeddedKafka(
        partitions = 1,
        topics = {"origination.prospect-created"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@DirtiesContext
class PartySearchIT {

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
        // Para contar sentencias JDBC en el test de performance.
        registry.add("spring.jpa.properties.hibernate.generate_statistics", () -> "true");
    }

    @Autowired PartyRepository partyRepository;
    @Autowired EntityManagerFactory emf;

    // ── Búsqueda por texto (pg_trgm) ──────────────────────────────────────────

    @Test
    void search_matchesByName_curp_andRfc() {
        String tok = "srch";
        Party garcia = save("Gabriela", "García", tok, "SRCHGARN900101X01", "SRCHGARN01", PartyType.INDIVIDUAL);
        save("Bruno", "Gómez", tok, "SRCHGORB900101X02", "SRCHGORB02", PartyType.INDIVIDUAL);

        // por nombre (contains, case-insensitive)
        Page<Party> byName = partyRepository.search("gabr", null, null, null, page());
        assertThat(byName.getContent()).extracting(Party::getPartyId).contains(garcia.getPartyId());
        assertThat(byName.getContent()).allMatch(p -> p.getFirstName().equalsIgnoreCase("Gabriela"));

        // por CURP
        Page<Party> byCurp = partyRepository.search("srchgarn900101", null, null, null, page());
        assertThat(byCurp.getContent()).extracting(Party::getPartyId).containsExactly(garcia.getPartyId());

        // por RFC
        Page<Party> byRfc = partyRepository.search("srchgarn01", null, null, null, page());
        assertThat(byRfc.getContent()).extracting(Party::getPartyId).containsExactly(garcia.getPartyId());
    }

    // ── Filtros por tipo y estado ─────────────────────────────────────────────

    @Test
    void search_filtersByTypeAndStatus() {
        String tok = "flt";
        Party biz = save("Empresa", "SA de CV", tok, "FLTBIZ0000000000A", "FLTBIZ01", PartyType.BUSINESS);
        Party ind = save("Persona", "Física", tok, "FLTIND0000000000B", "FLTIND01", PartyType.INDIVIDUAL);

        assertThat(partyRepository.search(tok, PartyType.BUSINESS, null, null, page()).getContent())
                .extracting(Party::getPartyId).containsExactly(biz.getPartyId());
        assertThat(partyRepository.search(tok, PartyType.INDIVIDUAL, null, null, page()).getContent())
                .extracting(Party::getPartyId).containsExactly(ind.getPartyId());
        assertThat(partyRepository.search(tok, null, null, null, page()).getTotalElements()).isEqualTo(2);

        // estado: blacklistear uno lo saca del filtro PROSPECT y lo mete en BLACKLISTED
        ind.blacklist("test");
        partyRepository.save(ind);
        assertThat(partyRepository.search(tok, null, PartyStatus.BLACKLISTED, null, page()).getContent())
                .extracting(Party::getPartyId).containsExactly(ind.getPartyId());
        assertThat(partyRepository.search(tok, null, PartyStatus.PROSPECT, null, page()).getContent())
                .extracting(Party::getPartyId).containsExactly(biz.getPartyId());
    }

    // ── Paginación real (la resuelve la base) ─────────────────────────────────

    @Test
    void search_paginatesInTheDatabase() {
        String tok = "pgn";
        for (int i = 0; i < 15; i++) {
            save("Cliente" + i, "Paginado", tok, "PGN%011d".formatted(i), "PGN%06d".formatted(i),
                    PartyType.INDIVIDUAL);
        }
        Page<Party> p0 = partyRepository.search(tok, null, null, null,
                PageRequest.of(0, 10, Sort.by("createdAt")));
        assertThat(p0.getTotalElements()).isEqualTo(15);
        assertThat(p0.getTotalPages()).isEqualTo(2);
        assertThat(p0.getContent()).hasSize(10);

        Page<Party> p1 = partyRepository.search(tok, null, null, null,
                PageRequest.of(1, 10, Sort.by("createdAt")));
        assertThat(p1.getContent()).hasSize(5);
    }

    // ── Filtro por ejecutivo de cuenta ────────────────────────────────────────

    @Test
    void search_filtersByAssignedExecutive() {
        String tok = "exe";
        UUID executiveId = UUID.randomUUID();
        Party assigned = save("Con", "Ejecutivo", tok, "EXE0000000000001", "EXE0001", PartyType.INDIVIDUAL);
        assigned.assignExecutive(executiveId, "Ana Torres");
        partyRepository.save(assigned);
        save("Sin", "Ejecutivo", tok, "EXE0000000000002", "EXE0002", PartyType.INDIVIDUAL);

        Page<Party> byExec = partyRepository.search(tok, null, null, executiveId, page());
        assertThat(byExec.getContent()).extracting(Party::getPartyId).containsExactly(assigned.getPartyId());
        assertThat(byExec.getContent()).allMatch(p -> "Ana Torres".equals(p.getAssignedExecutiveName()));

        // Sin filtro de ejecutivo, ambos aparecen.
        assertThat(partyRepository.search(tok, null, null, null, page()).getTotalElements()).isEqualTo(2);
    }

    // ── Hidratación por lote ──────────────────────────────────────────────────

    @Test
    void batch_returnsRequestedParties_byPartyId_andByProspectId_andEmpty() {
        String tok = "bat";
        List<Party> parties = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            parties.add(save("N" + i, "Batch", tok, "BAT%011d".formatted(i), "BAT%06d".formatted(i),
                    PartyType.INDIVIDUAL));
        }
        List<UUID> partyIds = parties.stream().map(Party::getPartyId).toList();
        List<UUID> prospectIds = parties.stream().map(Party::getProspectId).toList();

        assertThat(partyRepository.findByPartyIdIn(partyIds)).extracting(Party::getPartyId)
                .containsExactlyInAnyOrderElementsOf(partyIds);
        // Cartera guarda el prospectId como obligado → el batch por prospecto lo resuelve.
        assertThat(partyRepository.findByProspectIdIn(prospectIds)).extracting(Party::getPartyId)
                .containsExactlyInAnyOrderElementsOf(partyIds);

        assertThat(partyRepository.findByPartyIdIn(List.of())).isEmpty();
        assertThat(partyRepository.findByProspectIdIn(List.of())).isEmpty();
    }

    // ── Performance: /batch es O(1) en nº de sentencias ───────────────────────

    @Test
    void batch_resolvesManyPartiesInASingleStatement() {
        String tok = "perf";
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            ids.add(save("P" + i, "Perf", tok, "PERF%011d".formatted(i), "PERF%05d".formatted(i),
                    PartyType.INDIVIDUAL).getPartyId());
        }

        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();

        List<Party> got = partyRepository.findByPartyIdIn(ids);

        assertThat(got).hasSize(200);
        // Una sola sentencia (IN) para 200 filas: el coste no crece con el nº de filas.
        assertThat(stats.getPrepareStatementCount()).isEqualTo(1L);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static PageRequest page() {
        return PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    private Party save(String first, String last1, String last2, String curp, String rfc, PartyType type) {
        return partyRepository.save(Party.create(
                UUID.randomUUID(), UUID.randomUUID(), type,
                first, last1, last2, curp, rfc, LocalDate.of(1990, 1, 1)));
    }
}
