package com.fintech.beneficiary.infrastructure.adapter.in.api;

import com.fintech.beneficiary.application.port.in.PlacementLifecycleUseCase;
import com.fintech.beneficiary.application.port.out.PlacementRepository;
import com.fintech.beneficiary.application.service.PlacementOrchestrator;
import com.fintech.beneficiary.domain.*;
import com.fintech.beneficiary.infrastructure.config.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {PlacementController.class, BeneficiaryExceptionHandler.class})
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class PlacementControllerTest {

    private static final UUID DISTRIBUTOR = UUID.randomUUID();
    private static final UUID LINE        = UUID.randomUUID();
    private static final BigDecimal PAYMENT = new BigDecimal("868.06");
    private static final Instant EXPIRY     = Instant.now().plus(7, ChronoUnit.DAYS);
    /** Como los siembra `015-distributor-placement-limits.sql` para DL-DIST-STD-V1. */
    private static final PlacementLimits LIMITS = new PlacementLimits(
            new BigDecimal("5000"), new BigDecimal("60000"), 1000, 8, 16, 1);


    @Autowired MockMvc mockMvc;

    /**
     * La lista compone métricas por fila. Sin este stub el mock devuelve null y la respuesta
     * revienta — que es exactamente lo que pasaría en producción si el orquestador devolviera
     * null, así que el test no lo esconde: lo fija en "todavía no cobra nada".
     */
    @org.junit.jupiter.api.BeforeEach
    void stubMetrics() {
        given(orchestrator.metricsOf(any()))
                .willReturn(com.fintech.beneficiary.infrastructure.adapter.in.api.dto.PlacementMetrics.none());
    }

    @MockitoBean PlacementLifecycleUseCase placementLifecycle;
    @MockitoBean PlacementRepository placementRepository;
    // El controlador compone: la orquestación resuelve la línea y pide la disposición, y el
    // ensamblador arma el reporte de buró. Se simulan para que este slice siga probando lo suyo
    // —el contrato HTTP— sin levantar clientes hacia otros servicios.
    @MockitoBean PlacementOrchestrator orchestrator;
    @MockitoBean BureauReportAssembler bureauReportAssembler;

    private static Placement placement(UUID distributorPartyId) {
        return Placement.draft(distributorPartyId, LINE, "María Luisa Cortés Hernández", "5541829037",
                "Clienta de mi tienda desde 2023", new BigDecimal("18000"), 12, PAYMENT,
                VerificationMode.SELF_SERVICE_LINK, EXPIRY, LIMITS, null);
    }

    @Test
    @DisplayName("sin X-User-Id el gateway no pasó: 401")
    void withoutIdentityIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/placements"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("la respuesta cumple el contrato que la app ya parsea, campo por campo")
    void responseMatchesTheAppContract() throws Exception {
        given(placementRepository.findByDistributorPartyIdOrderByCreatedAtDesc(DISTRIBUTOR))
                .willReturn(List.of(placement(DISTRIBUTOR)));

        mockMvc.perform(get("/api/v1/placements")
                        .header("X-User-Id", DISTRIBUTOR.toString())
                        .header("X-Roles", "DISTRIBUTOR"))
                .andExpect(status().isOk())
                // Envoltura, no arreglo pelón.
                .andExpect(jsonPath("$.placements").isArray())
                // El estado va en el valor que la app conoce, no en el nombre del enum interno.
                .andExpect(jsonPath("$.placements[0].status").value("invited"))
                .andExpect(jsonPath("$.placements[0].beneficiaryName").value("María Luisa Cortés Hernández"))
                .andExpect(jsonPath("$.placements[0].beneficiaryPhoneMask").value("55 •• •• 90 37"))
                .andExpect(jsonPath("$.placements[0].relationship").value("Clienta de mi tienda desde 2023"))
                .andExpect(jsonPath("$.placements[0].amount").value(18000))
                .andExpect(jsonPath("$.placements[0].termFortnights").value(12))
                .andExpect(jsonPath("$.placements[0].fortnightlyPayment").value(868.06))
                .andExpect(jsonPath("$.placements[0].commissionAccrued").value(0))
                .andExpect(jsonPath("$.placements[0].paymentsMade").value(0))
                .andExpect(jsonPath("$.placements[0].daysPastDue").value(0))
                .andExpect(jsonPath("$.placements[0].inviteExpiresAt").isNotEmpty())
                // Invitar no crea expediente ni coloca nada.
                .andExpect(jsonPath("$.placements[0].beneficiaryPartyId").doesNotExist())
                .andExpect(jsonPath("$.placements[0].placedOn").doesNotExist());
    }

    @Test
    @DisplayName("el celular completo de un tercero no sale nunca en la respuesta")
    void theRawPhoneNeverLeaves() throws Exception {
        given(placementRepository.findByDistributorPartyIdOrderByCreatedAtDesc(DISTRIBUTOR))
                .willReturn(List.of(placement(DISTRIBUTOR)));

        String body = mockMvc.perform(get("/api/v1/placements")
                        .header("X-User-Id", DISTRIBUTOR.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("5541829037");
    }

    @Test
    @DisplayName("filtra por estado cuando se pide")
    void filtersByStatus() throws Exception {
        given(placementRepository.findByDistributorPartyIdAndStatusOrderByCreatedAtDesc(
                DISTRIBUTOR, PlacementStatus.BUREAU_READY)).willReturn(List.of());

        mockMvc.perform(get("/api/v1/placements")
                        .param("status", "BUREAU_READY")
                        .header("X-User-Id", DISTRIBUTOR.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.placements").isArray());
    }

    @Test
    @DisplayName("la colocación de otro distribuidor es 403, no 404")
    void someoneElsesPlacementIsForbidden() throws Exception {
        UUID placementId = UUID.randomUUID();
        given(placementLifecycle.findById(placementId)).willReturn(placement(UUID.randomUUID()));

        mockMvc.perform(get("/api/v1/placements/{id}", placementId)
                        .header("X-User-Id", DISTRIBUTOR.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("BENEFICIARY_PLACEMENT_FORBIDDEN"));
    }

    @Test
    @DisplayName("una colocación inexistente es 404 con ProblemDetail")
    void unknownPlacementIsNotFound() throws Exception {
        UUID placementId = UUID.randomUUID();
        given(placementLifecycle.findById(placementId))
                .willThrow(new PlacementNotFoundException(placementId));

        mockMvc.perform(get("/api/v1/placements/{id}", placementId)
                        .header("X-User-Id", DISTRIBUTOR.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("BENEFICIARY_PLACEMENT_NOT_FOUND"))
                .andExpect(jsonPath("$.type").value(
                        "https://fintech.com/errors/BENEFICIARY_PLACEMENT_NOT_FOUND"));
    }

    @Test
    @DisplayName("una transición imposible es 409 y dice de dónde a dónde")
    void invalidTransitionIsConflict() throws Exception {
        UUID placementId = UUID.randomUUID();
        willThrow(new InvalidPlacementTransitionException(
                placementId, PlacementStatus.EXPIRED, PlacementStatus.APPROVED))
                .given(placementLifecycle).findById(any());

        mockMvc.perform(get("/api/v1/placements/{id}", placementId)
                        .header("X-User-Id", DISTRIBUTOR.toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.fromStatus").value("EXPIRED"))
                .andExpect(jsonPath("$.toStatus").value("APPROVED"));
    }
}
