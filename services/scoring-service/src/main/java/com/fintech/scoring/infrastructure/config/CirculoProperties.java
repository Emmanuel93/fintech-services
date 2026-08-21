package com.fintech.scoring.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "fintech.circulo")
public class CirculoProperties {

    private String url = "https://services.circulodecredito.com.mx/sandbox";
    private String apiKey;
    private int timeoutSeconds = 30;
    /**
     * SOLO dev/local: cuando es true se usa {@code MockCirculoAdapter} (reporte determinista
     * por CURP) en vez de llamar al buró real. NUNCA activar en prod.
     */
    private boolean mockEnabled = false;

    public String getUrl()                { return url; }
    public void setUrl(String url)        { this.url = url; }
    public String getApiKey()             { return apiKey; }
    public void setApiKey(String apiKey)  { this.apiKey = apiKey; }
    public int getTimeoutSeconds()        { return timeoutSeconds; }
    public void setTimeoutSeconds(int t)  { this.timeoutSeconds = t; }
    public boolean isMockEnabled()        { return mockEnabled; }
    public void setMockEnabled(boolean v) { this.mockEnabled = v; }
}
