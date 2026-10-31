package com.fintech.compliance.infrastructure.adapter.out.government;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import com.fintech.compliance.application.port.out.GovernmentListPort;
import com.fintech.compliance.domain.CsvLayout;
import com.fintech.compliance.domain.WatchlistKind;
import com.fintech.compliance.domain.WatchlistLoadFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Descarga las listas de un proveedor real por HTTP, según {@link GovernmentListProviderProperties}.
 *
 * <p>Se registra con el nombre {@code governmentListAdapter} y sólo con
 * {@code fintech.compliance.provider.mode=http}; en ese caso el mock desaparece solo
 * ({@code @ConditionalOnMissingBean(name = "governmentListAdapter")}), sin tocar a quien usa el puerto.
 *
 * <h2>Qué hace y qué no</h2>
 *
 * Trae el archivo publicado para una fecha y declara su layout. No interpreta filas: eso es del
 * dominio ({@code WatchlistCsvParser}), igual para el mock que para el proveedor. Si la descarga
 * falla —HTTP distinto de 2xx, timeout, red— lanza {@link WatchlistLoadFailedException} y la
 * sincronización conserva la versión vigente: una lista a medio bajar nunca se activa.
 *
 * <p>El archivo se lee completo a memoria antes de entregarlo: así el hash que calcula la carga es
 * el de lo que llegó entero, no el de un flujo que pudo cortarse a la mitad.
 */
@Component("governmentListAdapter")
@ConditionalOnProperty(name = "fintech.compliance.provider.mode", havingValue = "http")
public class HttpGovernmentListAdapter implements GovernmentListPort {

    private static final Logger log = LoggerFactory.getLogger(HttpGovernmentListAdapter.class);

    private final GovernmentListProviderProperties properties;
    private final HttpClient http;

    public HttpGovernmentListAdapter(GovernmentListProviderProperties properties) {
        this.properties = properties;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        if (properties.sources().isEmpty()) {
            throw new IllegalStateException("fintech.compliance.provider.mode=http sin fuentes declaradas"
                    + " (fintech.compliance.provider.sources.<LISTA>.url)");
        }
        properties.sources().forEach((code, source) -> {
            if (source.url() == null || source.url().isBlank()) {
                throw new IllegalStateException("La lista " + code + " no tiene URL del proveedor");
            }
            layoutFor(code); // valida el layout al arrancar, no a las 02:30
        });
    }

    @Override
    public CsvLayout layoutFor(String sourceCode) {
        GovernmentListProviderProperties.Source s = source(sourceCode);
        return new CsvLayout(s.delimiter().charAt(0), s.resolvedCharset(), s.columns(), s.datePatterns());
    }

    @Override
    public InputStream download(String sourceCode, WatchlistKind kind, LocalDate publishedOn) {
        GovernmentListProviderProperties.Source s = source(sourceCode);
        String url = s.url()
                .replace("{date}", publishedOn.format(DateTimeFormatter.ofPattern(s.dateFormat())))
                .replace("{source}", sourceCode);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(properties.readTimeout())
                .GET();
        if (s.authHeader() != null && !s.authHeader().isBlank()) {
            request.header(s.authHeader(), s.authValue() == null ? "" : s.authValue());
        }
        long inicio = System.nanoTime();
        try {
            HttpResponse<byte[]> response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                throw new WatchlistLoadFailedException(sourceCode,
                        properties.name() + " respondió HTTP " + response.statusCode(), null);
            }
            byte[] body = response.body();
            log.info("[LISTAS {}] {} publicada {} — {} bytes en {} ms", properties.name(), sourceCode, publishedOn,
                    body.length, (System.nanoTime() - inicio) / 1_000_000);
            return new ByteArrayInputStream(body);
        } catch (IOException e) {
            throw new WatchlistLoadFailedException(sourceCode, properties.name() + " no respondió: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WatchlistLoadFailedException(sourceCode, "descarga interrumpida", e);
        }
    }

    @Override
    public String describeProvider() {
        return properties.name();
    }

    private GovernmentListProviderProperties.Source source(String sourceCode) {
        GovernmentListProviderProperties.Source s = properties.sources().get(sourceCode);
        if (s == null) {
            throw new WatchlistLoadFailedException(sourceCode,
                    "no está declarada en fintech.compliance.provider.sources", null);
        }
        return s;
    }
}
