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

    private final Cutoff cutoff = new Cutoff();

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
    public Cutoff getCutoff()            { return cutoff; }

    /**
     * El corte repartido por cuenta: una réplica planifica, todas trabajan.
     *
     * <p>El techo de paralelismo lo pone {@link #partitions}: Kafka entrega cada partición a un solo
     * consumidor del grupo, así que con 12 particiones caben hasta 12 hilos trabajando a la vez entre
     * todos los pods ({@code réplicas × concurrency}). Subir pods sin subir particiones deja hilos
     * ociosos.
     */
    public static class Cutoff {

        /**
         * La reanudación: cada 15 minutos corta, en orden, los días que cartera cerró y closing no —por
         * las mismas compuertas—, y vuelve a mandar los avisos de fin que no salieron. El disparo normal
         * es el aviso de cartera ({@code credit-portfolio.day-closed}).
         */
        private String cron = "0 15/15 * * * *";

        /** Cuánto espera en línea a que closing consuma los saldos de cartera antes de dejar el día en espera. */
        private Duration gateWait = Duration.ofMinutes(10);

        private Duration gatePoll = Duration.ofSeconds(2);

        /** Días que se guardan los movimientos con fecha valor. Un corte sólo mira los posteriores a su día. */
        private int movementRetentionDays = 62;

        /**
         * Si el corte espera a que este servicio haya sellado DELINQUENCY el mismo día.
         *
         * <p>Falso: el devengo y la mora los corren cargos y cartera en cadena desde las 00:05, y el
         * orden lo garantiza el aviso con que cartera dispara el corte. Esa barrera nunca se cumpliría.
         */
        private boolean requireDailyPhases = false;

        /** Cuántos días hacia atrás se buscan cortes que no corrieron (un pod caído, un cron perdido). */
        private int lookbackDays = 31;

        /** Particiones del tópico de trabajo. Es el techo de paralelismo entre todos los pods. */
        private int partitions = 12;

        /** Hilos consumidores por pod. */
        private int concurrency = 4;

        /** Cuántas unidades se publican por página al despachar. */
        private int dispatchPage = 1_000;

        /** Una unidad PENDING despachada hace más que esto se vuelve a mandar. */
        private Duration redispatchAfter = Duration.ofMinutes(2);

        /** Intentos por unidad antes de dejarla FAILED para revisión. */
        private int maxAttempts = 3;

        /** Permite planificar un corte con fecha futura. Sólo para pruebas que corren el reloj. */
        private boolean allowFutureDates = false;

        /**
         * Los productos cuyo titular es una distribuidora. El resto son de clientes. Es lo que separa
         * el corte —y el estado de cuenta— por cliente y por distribuidora.
         */
        private java.util.List<String> distributorProductTypes = new java.util.ArrayList<>(java.util.List.of("DISTRIBUTOR_LINE"));

        /** {@code DISTRIBUTOR} si el producto es de distribuidora; {@code CUSTOMER} si no. */
        public String holderKindOf(String productType) {
            return productType != null && distributorProductTypes.contains(productType) ? "DISTRIBUTOR" : "CUSTOMER";
        }

        public String getCron()                       { return cron; }
        public void setCron(String c)                 { this.cron = c; }
        public Duration getGateWait()                 { return gateWait; }
        public void setGateWait(Duration d)           { this.gateWait = d; }
        public Duration getGatePoll()                 { return gatePoll; }
        public void setGatePoll(Duration d)           { this.gatePoll = d; }
        public int getMovementRetentionDays()         { return movementRetentionDays; }
        public void setMovementRetentionDays(int d)   { this.movementRetentionDays = d; }
        public boolean isRequireDailyPhases()         { return requireDailyPhases; }
        public void setRequireDailyPhases(boolean r)  { this.requireDailyPhases = r; }
        public int getLookbackDays()                  { return lookbackDays; }
        public void setLookbackDays(int d)            { this.lookbackDays = d; }
        public int getPartitions()                    { return partitions; }
        public void setPartitions(int p)              { this.partitions = p; }
        public int getConcurrency()                   { return concurrency; }
        public void setConcurrency(int c)             { this.concurrency = c; }
        public int getDispatchPage()                  { return dispatchPage; }
        public void setDispatchPage(int p)            { this.dispatchPage = p; }
        public Duration getRedispatchAfter()          { return redispatchAfter; }
        public void setRedispatchAfter(Duration d)    { this.redispatchAfter = d; }
        public int getMaxAttempts()                   { return maxAttempts; }
        public void setMaxAttempts(int m)             { this.maxAttempts = m; }
        public java.util.List<String> getDistributorProductTypes()       { return distributorProductTypes; }
        public void setDistributorProductTypes(java.util.List<String> t) { this.distributorProductTypes = t; }
        public boolean isAllowFutureDates()           { return allowFutureDates; }
        public void setAllowFutureDates(boolean a)    { this.allowFutureDates = a; }
    }
}
