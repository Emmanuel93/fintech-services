package com.fintech.shared.scheduling;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.ITERABLE;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSupportSchedulingControllerTest {

    private static final Set<String> ALWAYS_ON = Set.of("closing.cutoff-run", "charges.accrual-monitor");

    private static MockEnvironment local() {
        return new MockEnvironment().withProperty("fintech.environment", "local");
    }

    @Test
    @DisplayName("en pausa: lo dice, con los crones de recuperación que siguen encendidos")
    void paused() {
        Map<String, Object> view = new TestSupportSchedulingController(local(), "charges-service", true, ALWAYS_ON, true)
                .view();

        assertThat(view).containsEntry("service", "charges-service").containsEntry("paused", true)
                .containsEntry("lockedCrons", true);
        assertThat(view.get("alwaysOn")).asInstanceOf(ITERABLE)
                .containsExactly("charges.accrual-monitor", "closing.cutoff-run");
    }

    @Test
    @DisplayName("sin pausa: lo dice, y una corrida determinista se niega a empezar")
    void notPaused() {
        Map<String, Object> view = new TestSupportSchedulingController(local(), "risk-service", false, ALWAYS_ON, true)
                .view();

        assertThat(view).containsEntry("paused", false);
        assertThat(view.get("alwaysOn")).asInstanceOf(ITERABLE).isEmpty();
    }

    @Test
    @DisplayName("nunca en producción")
    void neverInProduction() {
        MockEnvironment prod = new MockEnvironment().withProperty("fintech.environment", "prod");
        assertThatThrownBy(() -> new TestSupportSchedulingController(prod, "charges-service", true, ALWAYS_ON, true))
                .isInstanceOf(IllegalStateException.class);
    }
}
