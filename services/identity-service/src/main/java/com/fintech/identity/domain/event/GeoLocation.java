package com.fintech.identity.domain.event;

/**
 * Datos de geolocalización derivados de la IP del cliente.
 * Permite a auditoría y cumplimiento detectar accesos desde regiones inusuales
 * y cumplir con CNBV, PCI-DSS y GDPR.
 */
public record GeoLocation(

        /** ISO 3166-1 alpha-2 (e.g. "MX", "US"). "XX" si no se pudo resolver. */
        String countryCode,

        String countryName,
        String region,
        String city,
        String postalCode,

        Double latitude,
        Double longitude,

        /** Ej. "America/Mexico_City". */
        String timezone,

        /** Nombre del proveedor de internet (ISP). */
        String isp,

        /** Número de sistema autónomo (e.g. "AS13977"). */
        String asn,

        /** true si la IP parece pertenecer a una VPN conocida. */
        boolean vpn,

        /** true si la IP parece pertenecer a un proxy. */
        boolean proxy

) {
    /** Devuelve una instancia "desconocida" usada cuando la geolocalización falla o no está disponible. */
    public static GeoLocation unknown() {
        return new GeoLocation("XX", "Unknown", null, null, null,
                null, null, null, null, null, false, false);
    }
}
