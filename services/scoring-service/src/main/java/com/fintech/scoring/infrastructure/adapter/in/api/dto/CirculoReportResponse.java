package com.fintech.scoring.infrastructure.adapter.in.api.dto;

import com.fintech.scoring.domain.CirculoAddress;
import com.fintech.scoring.domain.CirculoCredit;
import com.fintech.scoring.domain.CirculoEmployment;
import com.fintech.scoring.domain.CirculoInquiry;
import com.fintech.scoring.domain.CirculoReport;
import com.fintech.scoring.domain.CirculoScore;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * El reporte de buró (Círculo de Crédito) para la mesa de análisis: la persona reportada, su
 * FICO, los créditos con su comportamiento de pago, empleos, domicilios, consultas recientes y
 * scores. Es el expediente que el analista revisa junto con el score y lo capturado.
 */
public record CirculoReportResponse(
        UUID    reportId,
        UUID    prospectId,
        String  status,
        String  folioConsulta,
        Instant queriedAt,
        String  errorCode,
        String  errorMessage,
        // Persona reportada por el buró
        String    personaNombres,
        String    personaApellidoPaterno,
        String    personaApellidoMaterno,
        LocalDate personaFechaNacimiento,
        String    personaRfc,
        String    personaCurp,
        String    personaSexo,
        String    personaEstadoCivil,
        String    personaNacionalidad,
        Integer   personaNumDependientes,
        Integer   ficoScoreValor,
        String    ficoScoreRazones,
        String    declaracionesConsumidor,
        List<Credit>     credits,
        List<Score>      scores,
        List<Inquiry>    inquiries,
        List<Employment> employments,
        List<Address>    addresses) {

    public record Credit(
            UUID       creditId,
            String     nombreOtorgante,
            String     tipoCredito,
            BigDecimal saldoActual,
            BigDecimal saldoVencido,
            BigDecimal limiteCredito,
            BigDecimal peorAtraso,
            BigDecimal saldoVencidoPeorAtraso,
            Integer    numeroPagosVencidos) {

        static Credit from(CirculoCredit c) {
            return new Credit(c.getCreditId(), c.getNombreOtorgante(), c.getTipoCredito(),
                    c.getSaldoActual(), c.getSaldoVencido(), c.getLimiteCredito(),
                    c.getPeorAtraso(), c.getSaldoVencidoPeorAtraso(), c.getNumeroPagosVencidos());
        }
    }

    public record Score(UUID scoreId, String nombreScore, Integer valor, String razones) {
        static Score from(CirculoScore s) {
            return new Score(s.getScoreId(), s.getNombreScore(), s.getValor(), s.getRazones());
        }
    }

    public record Inquiry(UUID inquiryId, LocalDate fechaConsulta, String nombreOtorgante,
                          String tipoCredito, BigDecimal importeCredito) {
        static Inquiry from(CirculoInquiry i) {
            return new Inquiry(i.getInquiryId(), i.getFechaConsulta(), i.getNombreOtorgante(),
                    i.getTipoCredito(), i.getImporteCredito());
        }
    }

    public record Employment(UUID employmentId, String nombreEmpresa, BigDecimal salarioMensual) {
        static Employment from(CirculoEmployment e) {
            return new Employment(e.getEmploymentId(), e.getNombreEmpresa(), e.getSalarioMensual());
        }
    }

    public record Address(UUID addressId, String direccion, String ciudad) {
        static Address from(CirculoAddress a) {
            return new Address(a.getAddressId(), a.getDireccion(), a.getCiudad());
        }
    }

    public static CirculoReportResponse from(CirculoReport r) {
        return new CirculoReportResponse(
                r.getReportId(),
                r.getProspectId(),
                r.getStatus() == null ? null : r.getStatus().name(),
                r.getFolioConsulta(),
                r.getQueriedAt(),
                r.getErrorCode(),
                r.getErrorMessage(),
                r.getPersonaNombres(),
                r.getPersonaApellidoPaterno(),
                r.getPersonaApellidoMaterno(),
                r.getPersonaFechaNacimiento(),
                r.getPersonaRfc(),
                r.getPersonaCurp(),
                r.getPersonaSexo(),
                r.getPersonaEstadoCivil(),
                r.getPersonaNacionalidad(),
                r.getPersonaNumDependientes(),
                r.getFicoScoreValor(),
                r.getFicoScoreRazones(),
                r.getDeclaracionesConsumidor(),
                r.getCredits().stream().map(Credit::from).toList(),
                r.getScores().stream().map(Score::from).toList(),
                r.getInquiries().stream().map(Inquiry::from).toList(),
                r.getEmployments().stream().map(Employment::from).toList(),
                r.getAddresses().stream().map(Address::from).toList());
    }
}
