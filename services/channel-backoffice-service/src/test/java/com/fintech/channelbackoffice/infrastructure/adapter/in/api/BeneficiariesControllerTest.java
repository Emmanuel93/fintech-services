package com.fintech.channelbackoffice.infrastructure.adapter.in.api;

import com.fintech.channelbackoffice.infrastructure.adapter.out.client.BeneficiaryClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.PartyClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * La bandeja de la mesa de KYC, y sobre todo <b>cuántas llamadas cuesta</b>.
 *
 * <p>El invariante del backoffice no es «que responda»: es que el número de llamadas a dominio no
 * dependa del número de filas. Por eso el test que más importa aquí no comprueba un campo sino un
 * conteo.
 */
@ExtendWith(MockitoExtension.class)
class BeneficiariesControllerTest {

    @Mock BeneficiaryClient beneficiaryClient;
    @Mock PartyClient partyClient;

    BeneficiariesController controller;

    @BeforeEach
    void setUp() {
        controller = new BeneficiariesController(beneficiaryClient, partyClient);
    }

    private static Map<String, Object> row(UUID distributorPartyId, String status, String identity) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("placementId", UUID.randomUUID());
        r.put("distributorPartyId", distributorPartyId);
        r.put("beneficiaryName", "María Luisa Cortés Hernández");
        r.put("beneficiaryPhoneMask", "55 •• •• 90 37");
        r.put("status", status);
        r.put("identityStatus", identity);
        return r;
    }

    private static Map<String, Object> page(List<Map<String, Object>> rows) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("content", rows);
        p.put("page", 0);
        p.put("size", 50);
        p.put("totalElements", (long) rows.size());
        p.put("totalPages", 1);
        return p;
    }

    /**
     * Una distribuidora. El nombre va entero en {@code firstName} porque
     * {@code BackofficeViews.fullName} concatena los tres campos: una persona moral no tiene
     * apellidos que separar.
     */
    private static PartyClient.PartyResponse party(UUID id, String name) {
        // `id` es el que la colocación guarda como `distributorPartyId`, que es el **prospectId**
        // —el `sub` del JWT del distribuidor—. El `partyId` propio de party-service es otro, y
        // por eso va distinto: un helper que los hiciera iguales escondería justamente el error
        // de resolver la bandeja por la clave equivocada.
        return new PartyClient.PartyResponse(UUID.randomUUID(), id, null, "BUSINESS", "ACTIVE",
                name, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    @DisplayName("50 filas de 3 distribuidoras cuestan 2 llamadas de dominio, no 51")
    void oneBatchCallRegardlessOfRowCount() {
        List<UUID> distributors = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            rows.add(row(distributors.get(i % 3), "KYC_IN_PROGRESS", "IN_PROGRESS"));
        }
        given(beneficiaryClient.searchPlacements(any(), any(), any(), any(), anyInt(), anyInt()))
                .willReturn(page(rows));
        given(partyClient.batch(any(), eq("prospectId")))
                .willReturn(distributors.stream().map(d -> party(d, "Distribuidora " + d)).toList());

        var response = controller.placements(null, null, null, null, 0, 50);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        // Exactamente una consulta al dueño y un /batch para los nombres.
        verify(beneficiaryClient, times(1)).searchPlacements(any(), any(), any(), any(), anyInt(), anyInt());
        verify(partyClient, times(1)).batch(any(), eq("prospectId"));
        verifyNoMoreInteractions(beneficiaryClient, partyClient);
    }

    @Test
    @DisplayName("el /batch se pide deduplicado: 50 filas, 3 distribuidoras, 3 ids")
    void batchIsDeduplicated() {
        List<UUID> distributors = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 50; i++) rows.add(row(distributors.get(i % 3), "INVITED", "PENDING"));

        given(beneficiaryClient.searchPlacements(any(), any(), any(), any(), anyInt(), anyInt()))
                .willReturn(page(rows));
        given(partyClient.batch(any(), eq("prospectId"))).willReturn(List.of());

        controller.placements(null, null, null, null, 0, 50);

        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass((Class<Collection<UUID>>) (Class<?>) Collection.class);
        verify(partyClient).batch(captor.capture(), eq("prospectId"));
        assertThat(captor.getValue()).hasSize(3);
    }

    @Test
    @DisplayName("cada fila queda con el nombre de su distribuidora")
    void rowsGetTheirDistributorName() {
        UUID distributor = UUID.randomUUID();
        given(beneficiaryClient.searchPlacements(any(), any(), any(), any(), anyInt(), anyInt()))
                .willReturn(page(List.of(row(distributor, "BUREAU_READY", "VERIFIED"))));
        given(partyClient.batch(any(), eq("prospectId")))
                .willReturn(List.of(party(distributor, "Distribuidora Cortés")));

        var body = controller.placements(null, null, null, null, 0, 50).getBody();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) body.get("content");
        assertThat(content).hasSize(1);
        assertThat(content.get(0).get("distributorName")).isEqualTo("Distribuidora Cortés");
        assertThat(content.get(0).get("identityStatus")).isEqualTo("VERIFIED");
    }

    @Test
    @DisplayName("si party-service falla, la bandeja sigue sirviendo: sin nombre, pero con el trabajo")
    void aFailingNameLookupDoesNotBreakTheQueue() {
        given(beneficiaryClient.searchPlacements(any(), any(), any(), any(), anyInt(), anyInt()))
                .willReturn(page(List.of(row(UUID.randomUUID(), "KYC_COMPLETED", "VERIFIED"))));
        given(partyClient.batch(any(), eq("prospectId")))
                .willThrow(new IllegalStateException("party-service caído"));

        var response = controller.placements(null, null, null, null, 0, 50);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content =
                (List<Map<String, Object>>) response.getBody().get("content");
        assertThat(content.get(0).get("distributorName")).isNull();
        assertThat(content.get(0).get("status")).isEqualTo("KYC_COMPLETED");
    }

    @Test
    @DisplayName("una bandeja vacía no pide nombres a nadie")
    void anEmptyQueueAsksForNoNames() {
        given(beneficiaryClient.searchPlacements(any(), any(), any(), any(), anyInt(), anyInt()))
                .willReturn(page(List.of()));

        controller.placements(null, null, null, null, 0, 50);

        verify(partyClient, never()).batch(any(), any());
    }
}
