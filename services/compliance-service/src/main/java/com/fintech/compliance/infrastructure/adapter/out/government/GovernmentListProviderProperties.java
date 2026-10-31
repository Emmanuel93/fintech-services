package com.fintech.compliance.infrastructure.adapter.out.government;

import java.nio.charset.Charset;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * El proveedor real de listas ({@code fintech.compliance.provider}), declarado y no programado.
 *
 * <p>Cada lista es una fuente con su URL, su autenticación y su layout. Conectar un proveedor nuevo
 * —un agregador como QeQ, el CSV público de OFAC, el de la lista 69-B del SAT— es llenar esta
 * configuración, no escribir un adaptador: el puerto ya entrega bytes y layout, y el parser del
 * dominio es el mismo que hoy ejercita el mock con archivos sucios.
 *
 * @param mode       {@code mock} (por defecto, sólo ambientes bajos) o {@code http}
 * @param name       cómo se llama el proveedor en el log y en la versión cargada («QeQ», «OFAC»)
 * @param sources    por código de lista ({@code OFAC-SDN}, {@code PEP-MX}, {@code SAT-69B})
 */
@ConfigurationProperties(prefix = "fintech.compliance.provider")
public record GovernmentListProviderProperties(
        String mode,
        String name,
        Duration connectTimeout,
        Duration readTimeout,
        Map<String, Source> sources) {

    public GovernmentListProviderProperties {
        mode = mode == null || mode.isBlank() ? "mock" : mode;
        name = name == null || name.isBlank() ? "proveedor-http" : name;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        // Un archivo de lista pesa megas: la lectura tiene más margen que una consulta.
        readTimeout = readTimeout == null ? Duration.ofSeconds(60) : readTimeout;
        sources = sources == null ? Map.of() : Map.copyOf(sources);
    }

    /**
     * Una lista del proveedor.
     *
     * @param url         plantilla: {@code {date}} se sustituye por la fecha de publicación con
     *                    {@code dateFormat}, y {@code {source}} por el código de la lista
     * @param authHeader  cabecera de la credencial ({@code Authorization}, {@code x-api-key}); vacía si es pública
     * @param authValue   la credencial; viene del ambiente, nunca del repo
     * @param dateFormat  formato de {@code {date}} en la URL; {@code yyyy-MM-dd} por defecto
     * @param delimiter   separador del archivo
     * @param charset     codificación del archivo (los portales de gobierno suelen entregar Latin-1)
     * @param columns     nombre lógico → encabezado del archivo; {@code name} es obligatorio
     * @param datePatterns formatos de fecha que trae el archivo
     */
    public record Source(String url, String authHeader, String authValue, String dateFormat,
                         String delimiter, String charset, Map<String, String> columns,
                         List<String> datePatterns) {

        public Source {
            dateFormat = dateFormat == null || dateFormat.isBlank() ? "yyyy-MM-dd" : dateFormat;
            delimiter = delimiter == null || delimiter.isEmpty() ? "," : delimiter;
            charset = charset == null || charset.isBlank() ? "UTF-8" : charset;
            columns = columns == null ? Map.of() : Map.copyOf(columns);
            datePatterns = datePatterns == null ? List.of() : List.copyOf(datePatterns);
        }

        Charset resolvedCharset() {
            return Charset.forName(charset);
        }
    }
}
