package com.fintech.scoring.application.service;

import com.fintech.scoring.application.port.out.ScoringPolicyRepository;
import com.fintech.scoring.application.service.ScoringPolicyService.ThresholdSpec;
import com.fintech.scoring.domain.InvalidScoringPolicyException;
import com.fintech.scoring.domain.NoHistoryPolicy;
import com.fintech.scoring.domain.NoHistoryRequirement;
import com.fintech.scoring.domain.RiskLevel;
import com.fintech.scoring.domain.ScoringDecision;
import com.fintech.scoring.domain.ScoringPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Lo que la política de un producto dice de quien no tiene historial en buró tiene que poder cumplirse.
 */
class ScoringPolicyNoHistoryTest {

    /** Las bandas que hoy tienen sembradas los productos de persona física. */
    private static final List<ThresholdSpec> BANDAS = List.of(
            new ThresholdSpec(RiskLevel.BAJO, 400, ScoringDecision.AUTO_APPROVED),
            new ThresholdSpec(RiskLevel.MEDIO, 300, ScoringDecision.MANUAL_REVIEW),
            new ThresholdSpec(RiskLevel.ALTO, -9999, ScoringDecision.REJECTED));

    private final ScoringPolicyRepository repository = mock(ScoringPolicyRepository.class);
    private final ScoringPolicyService service = new ScoringPolicyService(repository);

    @Test
    @DisplayName("score 0 sin datos adicionales: válido (cae en rechazo, como se decidió)")
    void scoreCeroEsValido() {
        assertThatCode(() -> ScoringPolicyService.validateNoHistory(NoHistoryPolicy.DEFAULT, BANDAS))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("producto para estudiantes: score en la banda de revisión y constancia de estudios — válido")
    void estudiantesEsValido() {
        NoHistoryPolicy estudiantes = new NoHistoryPolicy(300, List.of(NoHistoryRequirement.PROOF_OF_ENROLLMENT));

        assertThatCode(() -> ScoringPolicyService.validateNoHistory(estudiantes, BANDAS)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("sin historial no se puede configurar para aprobarse en automático")
    void noSeApruebaEnAutomatico() {
        assertThatThrownBy(() -> ScoringPolicyService.validateNoHistory(new NoHistoryPolicy(450, List.of()), BANDAS))
                .isInstanceOf(InvalidScoringPolicyException.class)
                .hasMessageContaining("no se aprueba en automático");
    }

    @Test
    @DisplayName("pedir datos a quien de todos modos se rechaza no tiene sentido")
    void datosConRechazoNoTienenSentido() {
        NoHistoryPolicy contradictoria = new NoHistoryPolicy(0, List.of(NoHistoryRequirement.INCOME_PROOF));

        assertThatThrownBy(() -> ScoringPolicyService.validateNoHistory(contradictoria, BANDAS))
                .isInstanceOf(InvalidScoringPolicyException.class)
                .hasMessageContaining("sólo se piden si alguien los revisa");
    }

    @Test
    @DisplayName("una política inválida no desactiva la vigente: el producto no se queda sin ninguna")
    void invalidaNoTocaLaVigente() {
        when(repository.findActiveBy(anyString())).thenReturn(Optional.of(mock(ScoringPolicy.class)));

        assertThatThrownBy(() -> service.create("INDIVIDUAL", "PERSONAL_LOAN", "v2", null, List.of(), BANDAS,
                new NoHistoryPolicy(450, List.of()))).isInstanceOf(InvalidScoringPolicyException.class);

        verify(repository, never()).save(any());
        verify(repository, never()).findActiveBy(anyString());
    }

    @Test
    @DisplayName("la política guarda lo que se le configuró y lo devuelve igual")
    void laPoliticaLoGuarda() {
        NoHistoryPolicy estudiantes = new NoHistoryPolicy(300,
                List.of(NoHistoryRequirement.PROOF_OF_ENROLLMENT, NoHistoryRequirement.GUARANTOR));

        ScoringPolicy p = ScoringPolicy.create(java.util.UUID.randomUUID(), "INDIVIDUAL", "STUDENT_LOAN",
                "Estudiantes", null, 1, estudiantes);

        assertThat(p.getNoHistory()).isEqualTo(estudiantes);
    }

    @Test
    @DisplayName("rendimiento: validar mil políticas es instantáneo")
    void validarEsBarato() {
        long inicio = System.nanoTime();
        for (int i = 0; i < 1_000; i++) {
            ScoringPolicyService.validateNoHistory(new NoHistoryPolicy(i % 400, List.of()), BANDAS);
        }
        assertThat((System.nanoTime() - inicio) / 1_000_000).isLessThan(500L);
    }
}
