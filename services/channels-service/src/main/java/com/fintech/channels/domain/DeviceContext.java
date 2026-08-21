package com.fintech.channels.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * Contexto de dispositivo capturado al iniciar la sesión.
 *
 * <p>IP y User-Agent se extraen siempre del lado servidor (cabeceras HTTP inyectadas
 * por el gateway). El resto de campos son auto-reportados por el cliente SDK/app.
 */
@Embeddable
public class DeviceContext {

    // ── Identidad del dispositivo ─────────────────────────────────────────────

    @Column(name = "device_id")
    private String deviceId;

    @Column(name = "device_type")
    private String deviceType;           // MOBILE | TABLET | DESKTOP | ATM | KIOSK | IVR | UNKNOWN

    @Column(name = "device_model")
    private String deviceModel;          // "iPhone 15 Pro", "Samsung Galaxy S24"

    @Column(name = "device_manufacturer")
    private String deviceManufacturer;   // "Apple", "Samsung"

    // ── Sistema operativo ─────────────────────────────────────────────────────

    @Column(name = "os")
    private String os;                   // "iOS", "Android", "Windows", "macOS"

    @Column(name = "os_version")
    private String osVersion;            // "17.2", "14", "11"

    // ── Versión de aplicación ─────────────────────────────────────────────────

    @Column(name = "app_version")
    private String appVersion;           // versión del app cliente: "2.1.3"

    @Column(name = "sdk_version")
    private String sdkVersion;           // versión del SDK de integración B2B: "3.0.1"

    // ── Red ───────────────────────────────────────────────────────────────────

    @Column(name = "network_type")
    private String networkType;          // WIFI | LTE | 5G | ETHERNET | UNKNOWN

    // ── HTTP / Navegador (extraídos server-side) ──────────────────────────────

    @Column(name = "user_agent", columnDefinition = "TEXT")
    private String userAgent;            // User-Agent completo de la cabecera HTTP

    @Column(name = "browser")
    private String browser;              // "Chrome", "Safari", "Firefox" (parseo simplificado)

    @Column(name = "browser_version")
    private String browserVersion;       // "120.0"

    // ── Red / IP (extraídos server-side del X-Forwarded-For) ─────────────────

    @Column(name = "ip_address")
    private String ipAddress;            // IPv4 o IPv6; máx 45 chars (::ffff:255.255.255.255)

    @Column(name = "ip_country")
    private String ipCountry;            // Código ISO 3166-1 alpha-2 inyectado por gateway ("MX", "US")

    // ── Indicadores de seguridad (auto-reportados por SDK) ────────────────────

    @Column(name = "is_trusted_device", nullable = false)
    private boolean isTrustedDevice;     // dispositivo previamente registrado y verificado

    @Column(name = "is_rooted", nullable = false)
    private boolean isRooted;            // Android rooted / iOS jailbroken

    @Column(name = "is_emulator", nullable = false)
    private boolean isEmulator;          // entorno de emulador/simulador detectado

    protected DeviceContext() {}

    private DeviceContext(Builder b) {
        this.deviceId           = b.deviceId;
        this.deviceType         = b.deviceType;
        this.deviceModel        = b.deviceModel;
        this.deviceManufacturer = b.deviceManufacturer;
        this.os                 = b.os;
        this.osVersion          = b.osVersion;
        this.appVersion         = b.appVersion;
        this.sdkVersion         = b.sdkVersion;
        this.networkType        = b.networkType;
        this.userAgent          = b.userAgent;
        this.browser            = b.browser;
        this.browserVersion     = b.browserVersion;
        this.ipAddress          = b.ipAddress;
        this.ipCountry          = b.ipCountry;
        this.isTrustedDevice    = b.isTrustedDevice;
        this.isRooted           = b.isRooted;
        this.isEmulator         = b.isEmulator;
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String deviceId;
        private String deviceType         = DeviceType.UNKNOWN.name();
        private String deviceModel;
        private String deviceManufacturer;
        private String os;
        private String osVersion;
        private String appVersion;
        private String sdkVersion;
        private String networkType;
        private String userAgent;
        private String browser;
        private String browserVersion;
        private String ipAddress;
        private String ipCountry;
        private boolean isTrustedDevice   = false;
        private boolean isRooted          = false;
        private boolean isEmulator        = false;

        public Builder deviceId(String v)           { this.deviceId = v; return this; }
        public Builder deviceType(String v)         { this.deviceType = v; return this; }
        public Builder deviceModel(String v)        { this.deviceModel = v; return this; }
        public Builder deviceManufacturer(String v) { this.deviceManufacturer = v; return this; }
        public Builder os(String v)                 { this.os = v; return this; }
        public Builder osVersion(String v)          { this.osVersion = v; return this; }
        public Builder appVersion(String v)         { this.appVersion = v; return this; }
        public Builder sdkVersion(String v)         { this.sdkVersion = v; return this; }
        public Builder networkType(String v)        { this.networkType = v; return this; }
        public Builder userAgent(String v)          { this.userAgent = v; return this; }
        public Builder browser(String v)            { this.browser = v; return this; }
        public Builder browserVersion(String v)     { this.browserVersion = v; return this; }
        public Builder ipAddress(String v)          { this.ipAddress = v; return this; }
        public Builder ipCountry(String v)          { this.ipCountry = v; return this; }
        public Builder isTrustedDevice(boolean v)   { this.isTrustedDevice = v; return this; }
        public Builder isRooted(boolean v)          { this.isRooted = v; return this; }
        public Builder isEmulator(boolean v)        { this.isEmulator = v; return this; }

        public DeviceContext build()                 { return new DeviceContext(this); }
    }

    public String getDeviceId()           { return deviceId; }
    public String getDeviceType()         { return deviceType; }
    public String getDeviceModel()        { return deviceModel; }
    public String getDeviceManufacturer() { return deviceManufacturer; }
    public String getOs()                 { return os; }
    public String getOsVersion()          { return osVersion; }
    public String getAppVersion()         { return appVersion; }
    public String getSdkVersion()         { return sdkVersion; }
    public String getNetworkType()        { return networkType; }
    public String getUserAgent()          { return userAgent; }
    public String getBrowser()            { return browser; }
    public String getBrowserVersion()     { return browserVersion; }
    public String getIpAddress()          { return ipAddress; }
    public String getIpCountry()          { return ipCountry; }
    public boolean isTrustedDevice()      { return isTrustedDevice; }
    public boolean isRooted()             { return isRooted; }
    public boolean isEmulator()           { return isEmulator; }
}
