package com.fintech.stp.rail;

import com.fintech.shared.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The settlement poller on demand, for the E2E: a simulated month lasts minutes, and the month-end close must find
 * every order of the month settled in treasury's cash book. Only exists with test support enabled.
 */
@SpringBootTest(properties = "fintech.test-support.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SettlementPollTestSupportIT extends AbstractIntegrationTest {

    @Override protected List<String> schemas() { return List.of("stp"); }
    /** The bank calendar is a catalog seeded by Liquibase (015); truncating it would date orders on holidays. */
    @Override protected List<String> preserve() { return List.of("business_calendar_days"); }

    @Autowired MockMvc mvc;

    @Test
    @DisplayName("con soporte de pruebas, la consulta de liquidaciones se dispara a pedido y responde rápido")
    void pollsOnDemand() throws Exception {
        long started = System.nanoTime();
        for (int i = 0; i < 20; i++) {
            mvc.perform(post("/internal/test-support/run-settlement-poll"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.appliedObservations").value(0));
        }
        long ms = (System.nanoTime() - started) / 1_000_000;
        assertThat(ms).as("20 consultas sin órdenes en vuelo en %d ms", ms).isLessThan(5_000);
    }
}
