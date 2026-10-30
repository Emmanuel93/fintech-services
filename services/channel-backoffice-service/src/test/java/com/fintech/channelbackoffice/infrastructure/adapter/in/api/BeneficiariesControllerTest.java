package com.fintech.channelbackoffice.infrastructure.adapter.in.api;

import com.fintech.channelbackoffice.infrastructure.adapter.out.client.BeneficiaryClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.ComplianceClient;
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
    @Mock ComplianceClient complianceClient;
    @Mock StaffIdentityResolver staffIdentities;

    BeneficiariesController controller;

    @BeforeEach
    void setUp() {
        controller = new BeneficiariesController(beneficiaryClient, partyClient, complianceClient, staffIdentities);
    }

    @org.junit.jupiter.api.AfterEach
    void sinSesion() { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }

    private static void sesionDe(String userId) {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        userId, null, List.of()));
    }

    /** La respuesta de beneficiary tras dictaminar: la colocación con el Party de la beneficiaria. */
    private static Map<String, Object> dictaminada(UUID beneficiaryPartyId) {
        Map<String, Object> placement = new LinkedHashMap<>();
        placement.put("beneficiaryPartyId", beneficiaryPartyId);
        placement.put("identityStatus", "VERIFIED");
        return new LinkedHashMap<>(Map.of("placement", placement, "verificationStrategy", "MANUAL"));
    }

    private static PartyClient.PartyResponse beneficiaria(UUID partyId, UUID prospectId) {
        return new PartyClient.PartyResponse(partyId, prospectId, null, "INDIVIDUAL", "ACTIVE",
                "Rosa Elena", "Díaz", "Fuentes", null, null, null, null, null, null, null, null);
    }

    // ── El dictamen de la colocación llega al veredicto de la persona ───────────────────────

    @Test
    @DisplayName("comprobar la identidad en la colocación la comprueba también en compliance, con el mismo autor")
    void aVerifiedPlacementMirrorsToCompliance() {
        sesionDe("staff-ops-1");
        UUID placementId = UUID.randomUUID(), partyId = UUID.randomUUID(), prospectId = UUID.randomUUID();
        UUID verificationId = UUID.randomUUID();
        given(beneficiaryClient.reviewIdentity(placementId, "VERIFIED", "staff-ops-1", null, null))
                .willReturn(dictaminada(partyId));
        given(partyClient.batch(List.of(partyId), "partyId")).willReturn(List.of(beneficiaria(partyId, prospectId)));
        given(complianceClient.byProspect(prospectId)).willReturn(Optional.of(
                Map.of("verificationId", verificationId.toString(), "identityStatus", "IN_REVIEW")));
        given(complianceClient.decideIdentity(eq(verificationId), eq("VERIFIED"), eq("staff-ops-1"), any()))
                .willReturn(Map.of("identityStatus", "VERIFIED"));

        var body = controller.reviewIdentity(placementId,
                new BeneficiariesController.IdentityReviewRequest("VERIFIED", null, null)).getBody();

        assertThat(body.get("complianceSync")).isEqualTo(Map.of("done", true, "identityStatus", "VERIFIED"));
        verify(complianceClient).decideIdentity(eq(verificationId), eq("VERIFIED"), eq("staff-ops-1"),
                org.mockito.ArgumentMatchers.contains(placementId.toString()));
    }

    @Test
    @DisplayName("el dictamen de la colocación se firma con el correo del staff, no con su id")
    void theVerdictIsSignedWithTheEmail() {
        sesionDe("staff-ops-1");
        given(staffIdentities.resolve("staff-ops-1")).willReturn(Optional.of(
                new StaffIdentityResolver.StaffIdentity("ops@kredius.mx", "Olivia Paz", null)));
        UUID placementId = UUID.randomUUID();
        given(beneficiaryClient.reviewIdentity(placementId, "VERIFIED", "ops@kredius.mx", null, null))
                .willReturn(dictaminada(null));

        controller.reviewIdentity(placementId, new BeneficiariesController.IdentityReviewRequest("VERIFIED", null, null));

        verify(beneficiaryClient).reviewIdentity(placementId, "VERIFIED", "ops@kredius.mx", null, null);
    }

    @Test
    @DisplayName("si compliance ya la dictaminó, no se firma encima")
    void anAlreadyDecidedPersonIsNotSignedAgain() {
        sesionDe("staff-ops-1");
        UUID placementId = UUID.randomUUID(), partyId = UUID.randomUUID(), prospectId = UUID.randomUUID();
        given(beneficiaryClient.reviewIdentity(any(), any(), any(), any(), any())).willReturn(dictaminada(partyId));
        given(partyClient.batch(List.of(partyId), "partyId")).willReturn(List.of(beneficiaria(partyId, prospectId)));
        given(complianceClient.byProspect(prospectId)).willReturn(Optional.of(
                Map.of("verificationId", UUID.randomUUID().toString(), "identityStatus", "VERIFIED")));

        controller.reviewIdentity(placementId, new BeneficiariesController.IdentityReviewRequest("VERIFIED", null, null));

        verify(complianceClient, never()).decideIdentity(any(), any(), any(), any());
    }

    @Test
    @DisplayName("si compliance no contesta, el dictamen de la colocación queda y se informa sin fallar")
    void aComplianceOutageDoesNotLoseTheVerdict() {
        sesionDe("staff-ops-1");
        UUID partyId = UUID.randomUUID();
        given(beneficiaryClient.reviewIdentity(any(), any(), any(), any(), any())).willReturn(dictaminada(partyId));
        given(partyClient.batch(List.of(partyId), "partyId"))
                .willReturn(List.of(beneficiaria(partyId, UUID.randomUUID())));
        given(complianceClient.byProspect(any())).willThrow(new IllegalStateException("compliance caído"));

        var response = controller.reviewIdentity(UUID.randomUUID(),
                new BeneficiariesController.IdentityReviewRequest("VERIFIED", null, null));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        var sync = (Map<String, Object>) response.getBody().get("complianceSync");
        assertThat(sync.get("done")).isEqualTo(false);
        assertThat(response.getBody().get("placement")).isNotNull();
    }

    @Test
    @DisplayName("un rechazo viaja a compliance con su motivo")
    void aRejectionTravelsWithItsReason() {
        sesionDe("staff-ops-1");
        UUID partyId = UUID.randomUUID(), prospectId = UUID.randomUUID(), verificationId = UUID.randomUUID();
        given(beneficiaryClient.reviewIdentity(any(), eq("REJECTED"), any(), eq("La selfie no es la de la INE"), any()))
                .willReturn(dictaminada(partyId));
        given(partyClient.batch(List.of(partyId), "partyId")).willReturn(List.of(beneficiaria(partyId, prospectId)));
        given(complianceClient.byProspect(prospectId)).willReturn(Optional.of(
                Map.of("verificationId", verificationId.toString(), "identityStatus", "IN_REVIEW")));

        controller.reviewIdentity(UUID.randomUUID(), new BeneficiariesController.IdentityReviewRequest(
                "REJECTED", "La selfie no es la de la INE", null));

        verify(complianceClient).decideIdentity(eq(verificationId), eq("REJECTED"), eq("staff-ops-1"),
                org.mockito.ArgumentMatchers.contains("La selfie no es la de la INE"));
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
