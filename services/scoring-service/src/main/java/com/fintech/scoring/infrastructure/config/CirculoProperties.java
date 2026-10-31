package com.fintech.scoring.infrastructure.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Conexión al buró ({@code fintech.circulo}).
 *
 * <p>Los tiempos van aquí y no en el código porque dependen del contrato y de la red del ambiente,
 * no del dominio. Antes existían {@code timeout-seconds} y {@code circulo-query-timeout-ms} y no los
 * leía nadie: el cliente HTTP no tenía límite y un buró colgado bloqueaba el listener del alta.
 */
@Component
@ConfigurationProperties(prefix = "fintech.circulo")
public class CirculoProperties {

    private String url = "https://services.circulodecredito.com.mx/sandbox";
    /** Sin valor por defecto a propósito: la credencial viene del ambiente, nunca del repo. */
    private String apiKey;
    /** Cuánto se espera para abrir la conexión. Corto: si no conecta pronto, no va a conectar. */
    private Duration connectTimeout = Duration.ofSeconds(3);
    /** Cuánto se espera la respuesta. La consulta del buró tarda segundos, no minutos. */
    private Duration readTimeout = Duration.ofSeconds(10);
    /**
     * SOLO dev/local: cuando es true se usa {@code MockCirculoAdapter} (reporte determinista
     * por CURP) en vez de llamar al buró real. Su guarda de arranque impide usarlo en producción.
     */
    private boolean mockEnabled = false;
    private final Retry retry = new Retry();

    /** Reintento ante fallas transitorias del buró: timeout, conexión, 5xx, 429. */
    public static class Retry {
        /** Reintentos después del primer intento: 3 ⇒ hasta 4 llamadas. */
        private int maxRetries = 3;
        private Duration initialBackoff = Duration.ofSeconds(1);
        private double multiplier = 2.0;
        private Duration maxBackoff = Duration.ofSeconds(10);

        public int getMaxRetries()                  { return maxRetries; }
        public void setMaxRetries(int v)            { this.maxRetries = v; }
        public Duration getInitialBackoff()         { return initialBackoff; }
        public void setInitialBackoff(Duration v)   { this.initialBackoff = v; }
        public double getMultiplier()               { return multiplier; }
        public void setMultiplier(double v)         { this.multiplier = v; }
        public Duration getMaxBackoff()             { return maxBackoff; }
        public void setMaxBackoff(Duration v)       { this.maxBackoff = v; }
    }

    public String getUrl()                          { return url; }
    public void setUrl(String url)                  { this.url = url; }
    public String getApiKey()                       { return apiKey; }
    public void setApiKey(String apiKey)            { this.apiKey = apiKey; }
    public Duration getConnectTimeout()             { return connectTimeout; }
    public void setConnectTimeout(Duration v)       { this.connectTimeout = v; }
    public Duration getReadTimeout()                { return readTimeout; }
    public void setReadTimeout(Duration v)          { this.readTimeout = v; }
    public boolean isMockEnabled()                  { return mockEnabled; }
    public void setMockEnabled(boolean v)           { this.mockEnabled = v; }
    public Retry getRetry()                         { return retry; }
}
