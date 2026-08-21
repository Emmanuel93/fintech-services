package com.fintech.beneficiary.infrastructure.adapter.in.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fintech.beneficiary.domain.PlacementNotReadyException;
import com.fintech.beneficiary.domain.Placement;
import com.fintech.beneficiary.domain.PlacementStatus;
import com.fintech.beneficiary.infrastructure.adapter.in.api.dto.BureauReportResponse;
import com.fintech.beneficiary.infrastructure.adapter.out.client.ScoringClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Convierte el reporte de círculo en las cinco líneas que lee un distribuidor.
 *
 * <p>El reporte crudo trae decenas de campos por crédito, y ninguno de ellos es una respuesta a
 * la única pregunta que él se hace: «¿le presto?». Este ensamblador destila eso — el peor atraso,
 * cuántos créditos trae y cómo van, qué tanto debe contra lo que gana, y cuántas veces lo han
 * consultado— y le pone una lectura en palabras.
 *
 * <p><b>La recomendación no es una decisión.</b> Se calcula del score y se entrega como un dato
 * más; el botón de aprobar está habilitado igual en las tres bandas.
 */
@Component
class BureauReportAssembler {

    private final ScoringClient scoring;

    BureauReportAssembler(ScoringClient scoring) {
        this.scoring = scoring;
    }

    BureauReportResponse assemble(Placement placement) {
        if (placement.getStatus() != PlacementStatus.BUREAU_READY
                && placement.getStatus().ordinal() < PlacementStatus.BUREAU_READY.ordinal()) {
            // Un reporte vacío se leería como historial limpio, y esa confusión cuesta dinero.
            throw new PlacementNotReadyException(
                    "La verificación de " + firstName(placement) + " sigue en curso");
        }
        if (placement.getBeneficiaryProspectId() == null) {
            throw new PlacementNotReadyException(
                    "Todavía no hay expediente de " + firstName(placement));
        }

        JsonNode report = scoring.reportOf(placement.getBeneficiaryProspectId())
                .orElseThrow(() -> new PlacementNotReadyException(
                        "El buró de " + firstName(placement) + " todavía no responde"));

        int score = score(report);
        Band band = Band.of(score);

        return new BureauReportResponse(score, band.wire, band.label,
                band.title, band.description, facts(report, band));
    }

    private static int score(JsonNode report) {
        JsonNode fico = report.path("ficoScoreValor");
        if (!fico.isMissingNode() && !fico.isNull()) return fico.asInt();
        // Algunos reportes traen el score sólo en la lista `scores`.
        for (JsonNode s : report.path("scores")) {
            if (!s.path("valor").isMissingNode()) return s.path("valor").asInt();
        }
        return 0;
    }

    private List<BureauReportResponse.Fact> facts(JsonNode report, Band band) {
        List<BureauReportResponse.Fact> facts = new ArrayList<>();

        int worstDelay = 0;
        int live = 0;
        int current = 0;
        double balance = 0;
        double overdue = 0;
        for (JsonNode c : report.path("credits")) {
            double saldo = c.path("saldoActual").asDouble(0);
            double vencido = c.path("saldoVencido").asDouble(0);
            int atraso = (int) c.path("peorAtraso").asDouble(0);
            worstDelay = Math.max(worstDelay, atraso);
            if (saldo > 0) {
                live++;
                balance += saldo;
                if (vencido <= 0) current++;
            }
            overdue += vencido;
        }

        facts.add(new BureauReportResponse.Fact("Peor atraso en 24 meses",
                worstDelay == 0 ? "Ninguno" : worstDelay + (worstDelay == 1 ? " día" : " días"),
                worstDelay == 0 ? "ok" : worstDelay <= 30 ? "warn" : "bad"));

        facts.add(new BureauReportResponse.Fact("Créditos vigentes",
                live == 0 ? "Ninguno"
                        : live + (current == live ? ", todos al corriente"
                                                  : ", " + (live - current) + " vencido" + (live - current == 1 ? "" : "s")),
                live == 0 || current == live ? "ok" : "bad"));

        double income = monthlyIncome(report);
        facts.add(new BureauReportResponse.Fact("Deuda contra su ingreso",
                income > 0 ? Math.round(balance / (income * 12) * 100) + "%" : "Sin dato",
                income > 0 && balance / (income * 12) > 0.5 ? "bad" : "ok"));

        int inquiries = report.path("inquiries").size();
        facts.add(new BureauReportResponse.Fact("Consultas en 6 meses",
                String.valueOf(inquiries), inquiries > 6 ? "warn" : "ok"));

        // Va al final y se llama recomendación, no decisión: el botón de aprobar no la consulta.
        facts.add(new BureauReportResponse.Fact("Recomendación de Kredius",
                band.recommendation, band.tone));

        if (overdue > 0) {
            facts.add(new BureauReportResponse.Fact("Saldo vencido hoy",
                    "$" + Math.round(overdue), "bad"));
        }
        return facts;
    }

    private static double monthlyIncome(JsonNode report) {
        double max = 0;
        for (JsonNode e : report.path("employments")) {
            max = Math.max(max, e.path("salarioMensual").asDouble(0));
        }
        return max;
    }

    private static String firstName(Placement p) {
        String full = p.getBeneficiaryFullName();
        if (full == null || full.isBlank()) return "la beneficiaria";
        return full.split("\\s+")[0];
    }

    /**
     * Las tres bandas del semáforo. Los cortes son los del diseño: por debajo de 620 el historial
     * ya pesa, por arriba de 700 está limpio, y en medio está el caso que obliga a decidir.
     */
    private enum Band {
        LOW("low", "RIESGO ALTO", "Se ha atrasado seguido",
            "Trae créditos vencidos o atrasos largos en los últimos dos años. Colocarle es "
            + "apostar tu línea.", "No colocar", "bad"),
        MEDIUM("medium", "RIESGO MEDIO", "Paga, pero se ha atrasado",
            "Cumple con lo que trae vigente, con algún atraso en los últimos dos años. No es un "
            + "historial limpio ni uno malo.", "Colocar con reserva", "warn"),
        HIGH("high", "RIESGO BAJO", "Paga puntual y sin sobresaltos",
            "Sus créditos están al corriente y no registra atrasos relevantes en los últimos dos "
            + "años.", "Colocar", "ok");

        final String wire, label, title, description, recommendation, tone;

        Band(String wire, String label, String title, String description,
             String recommendation, String tone) {
            this.wire = wire; this.label = label; this.title = title;
            this.description = description; this.recommendation = recommendation; this.tone = tone;
        }

        static Band of(int score) {
            if (score < 620) return LOW;
            if (score < 700) return MEDIUM;
            return HIGH;
        }
    }
}
