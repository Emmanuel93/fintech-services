package com.fintech.scoring.infrastructure.adapter.in.api;

import com.fintech.scoring.application.port.in.FindCirculoReportUseCase;
import com.fintech.scoring.domain.CirculoCredit;
import com.fintech.scoring.domain.CirculoReport;
import com.fintech.scoring.domain.CirculoReportStatus;
import com.fintech.scoring.domain.CirculoScore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BureauReportController.class)
class BureauReportControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean FindCirculoReportUseCase findCirculoReportUseCase;

    private final UUID prospectId = UUID.randomUUID();

    @Test
    void byProspect_returnsReportWithCreditsAndScores() throws Exception {
        given(findCirculoReportUseCase.findByProspectId(prospectId))
                .willReturn(Optional.of(sampleReport()));

        mockMvc.perform(get("/api/v1/scoring/reports/by-prospect/{id}", prospectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prospectId").value(prospectId.toString()))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.personaNombres").value("JUAN"))
                .andExpect(jsonPath("$.ficoScoreValor").value(720))
                .andExpect(jsonPath("$.credits[0].nombreOtorgante").value("BANCO XYZ"))
                .andExpect(jsonPath("$.credits[0].saldoActual").value(15000))
                .andExpect(jsonPath("$.credits[0].numeroPagosVencidos").value(0))
                .andExpect(jsonPath("$.scores[0].valor").value(720));
    }

    @Test
    void byProspect_notFound_returns404() throws Exception {
        given(findCirculoReportUseCase.findByProspectId(any(UUID.class)))
                .willReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/scoring/reports/by-prospect/{id}", prospectId))
                .andExpect(status().isNotFound());
    }

    private CirculoReport sampleReport() {
        CirculoReport r = CirculoReport.builder(UUID.randomUUID(), UUID.randomUUID(), prospectId)
                .status(CirculoReportStatus.SUCCESS)
                .folioConsulta("FC-123")
                .personaNombres("JUAN")
                .personaApellidoPaterno("PEREZ")
                .personaApellidoMaterno("GARCIA")
                .personaRfc("PEGJ900101AB1")
                .ficoScoreValor(720)
                .ficoScoreRazones("Historial sano")
                .build();
        r.addScore(new CirculoScore(UUID.randomUUID(), r, "FICO", 720, "Historial sano"));
        r.addCredit(sampleCredit());
        return r;
    }

    private CirculoCredit sampleCredit() {
        return new CirculoCredit(
                UUID.randomUUID(), null,
                null, "BANCO XYZ", null,
                null, null, "TDC",
                null, null,
                null, null, null,
                null, null, null,
                null, null, null,
                null, new BigDecimal("15000"), new BigDecimal("20000"),
                new BigDecimal("0"), 0, null,
                null, null, null,
                null, null,
                new BigDecimal("1"), null, new BigDecimal("0"),
                null, null, null);
    }
}
