package com.fintech.closing.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "fintech.closing")
public class ClosingProperties {

    private String calendarCode = "MX";

    /**
     * Identidad del pod en el arrendamiento. En Kubernetes la inyecta la downward API; en local,
     * el hostname. Dos pods con el mismo id harían indistinguibles sus arrendamientos.
     */
    private String nodeId = "local";

    private int batchSize = 500;

    /**
     * Cuánto dura el arrendamiento de una unidad. Tiene que ser mayor que el trabajo más lento y
     * menor que lo que se está dispuesto a esperar si un pod muere.
     */
    private Duration lease = Duration.ofMinutes(5);

    private int workers = 4;

    public String getCalendarCode()      { return calendarCode; }
    public void setCalendarCode(String c) { this.calendarCode = c; }
    public String getNodeId()            { return nodeId; }
    public void setNodeId(String n)      { this.nodeId = n; }
    public int getBatchSize()            { return batchSize; }
    public void setBatchSize(int b)      { this.batchSize = b; }
    public Duration getLease()           { return lease; }
    public void setLease(Duration l)     { this.lease = l; }
    public int getWorkers()              { return workers; }
    public void setWorkers(int w)        { this.workers = w; }
}
