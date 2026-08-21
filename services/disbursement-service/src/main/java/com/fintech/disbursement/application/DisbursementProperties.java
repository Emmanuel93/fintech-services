package com.fintech.disbursement.application;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Configuración de negocio del servicio.
 *
 * <p>Los <strong>nombres de topics no viven aquí</strong>: son detalle de transporte y están en
 * {@code infrastructure.config.DisbursementTopicProperties}. Meterlos en el paquete de aplicación
 * sería exactamente el acoplamiento de capas que este servicio evita.
 */
@ConfigurationProperties(prefix = "fintech.disbursement")
@Validated
public class DisbursementProperties {

    private final Dispatch dispatch = new Dispatch();

    /** Configuración por rail: ventana operativa y si está habilitado. Clave = nombre del rail. */
    private Map<String, RailConfig> rails = new LinkedHashMap<>();

    public static class Dispatch {
        private boolean enabled = true;
        /** Cada cuánto corre el job que despacha lo pendiente. */
        @NotNull
        private Duration interval = Duration.ofSeconds(5);
        @Min(1)
        private int batchSize = 50;
        /** Agotados los intentos, la orden va a {@code FAILED} y alguien tiene que mirarla. */
        @Min(1)
        private int maxAttempts = 6;
        /** Base del backoff exponencial entre intentos de despacho. */
        @NotNull
        private Duration retryBackoff = Duration.ofSeconds(15);

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public Duration getInterval() { return interval; }
        public void setInterval(Duration interval) { this.interval = interval; }
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
        public Duration getRetryBackoff() { return retryBackoff; }
        public void setRetryBackoff(Duration retryBackoff) { this.retryBackoff = retryBackoff; }
    }

    public static class RailConfig {
        private boolean enabled = true;
        private final Window window = new Window();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public Window getWindow() { return window; }
    }

    /**
     * Ventana operativa. Si {@code start > end} es envolvente: abre un día y cierra al siguiente.
     * Para SPEI el default cubre casi 24 h; la franja cerrada es el corte diario de Banxico.
     */
    public static class Window {
        @NotNull
        private LocalTime start = LocalTime.of(17, 10);
        @NotNull
        private LocalTime end = LocalTime.of(16, 50);
        @NotNull
        private ZoneId zone = ZoneId.of("America/Mexico_City");
        @NotNull
        private Set<DayOfWeek> days = EnumSet.allOf(DayOfWeek.class);

        public LocalTime getStart() { return start; }
        public void setStart(LocalTime start) { this.start = start; }
        public LocalTime getEnd() { return end; }
        public void setEnd(LocalTime end) { this.end = end; }
        public ZoneId getZone() { return zone; }
        public void setZone(ZoneId zone) { this.zone = zone; }
        public Set<DayOfWeek> getDays() { return days; }
        public void setDays(Set<DayOfWeek> days) { this.days = days; }
    }

    public Dispatch getDispatch() { return dispatch; }
    public Map<String, RailConfig> getRails() { return rails; }
    public void setRails(Map<String, RailConfig> rails) { this.rails = rails; }
}
