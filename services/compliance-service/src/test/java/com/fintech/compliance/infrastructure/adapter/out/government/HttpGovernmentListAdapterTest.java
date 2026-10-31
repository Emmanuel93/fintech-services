package com.fintech.compliance.infrastructure.adapter.out.government;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.fintech.compliance.domain.CsvLayout;
import com.fintech.compliance.domain.WatchlistCsvParser;
import com.fintech.compliance.domain.WatchlistEntry;
import com.fintech.compliance.domain.WatchlistKind;
import com.fintech.compliance.domain.WatchlistLoadFailedException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * El proveedor real de listas, contra un servidor HTTP local: lo que se configura es lo que se pide,
 * el archivo que llega lo lee el mismo parser del dominio, y una falla nunca activa media lista.
 */
class HttpGovernmentListAdapterTest {

    private HttpServer server;
    private final AtomicReference<String> pathPedido = new AtomicReference<>();
    private final AtomicReference<String> credencial = new AtomicReference<>();
    private volatile int status = 200;
    private volatile long demoraMs = 0;

    private static final String CSV = "NOMBRE|CURP|CARGO\n"
            + "PÉREZ LÓPEZ JUAN|PELJ800101HDFRPN09|SECRETARIO\n"
            + "GARCÍA RUIZ ANA|GARA750505MDFRZN02|DIPUTADA\n";

    @BeforeEach
    void levantar() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            pathPedido.set(ex.getRequestURI().toString());
            credencial.set(ex.getRequestHeaders().getFirst("Authorization"));
            try { Thread.sleep(demoraMs); } catch (InterruptedException ignored) { }
            byte[] body = CSV.getBytes(StandardCharsets.ISO_8859_1);
            ex.sendResponseHeaders(status, status == 200 ? body.length : -1);
            if (status == 200) ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void bajar() { server.stop(0); }

    private HttpGovernmentListAdapter adaptador(Duration lectura) {
        var fuente = new GovernmentListProviderProperties.Source(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/listas/{source}?fecha={date}",
                "Authorization", "Bearer token-de-prueba", "yyyyMMdd", "|", "ISO-8859-1",
                Map.of(CsvLayout.COL_NAME, "NOMBRE", CsvLayout.COL_CURP, "CURP", CsvLayout.COL_PROGRAM, "CARGO"),
                List.of());
        return new HttpGovernmentListAdapter(new GovernmentListProviderProperties(
                "http", "QeQ", Duration.ofSeconds(2), lectura, Map.of("PEP-MX", fuente)));
    }

    @Test
    void pide_la_fecha_de_publicacion_con_su_credencial_y_el_dominio_lee_el_archivo() throws Exception {
        var adapter = adaptador(Duration.ofSeconds(5));

        List<WatchlistEntry> leidas = new ArrayList<>();
        int lineas = new WatchlistCsvParser(adapter.layoutFor("PEP-MX")).parse(
                adapter.download("PEP-MX", WatchlistKind.PEP, LocalDate.of(2026, 10, 30)),
                UUID.randomUUID(), WatchlistKind.PEP, (e, n) -> leidas.add(e), r -> { });

        assertThat(pathPedido.get()).isEqualTo("/listas/PEP-MX?fecha=20261030");
        assertThat(credencial.get()).isEqualTo("Bearer token-de-prueba");
        assertThat(lineas).isEqualTo(2);
        assertThat(leidas).hasSize(2);
        assertThat(adapter.describeProvider()).isEqualTo("QeQ");
    }

    @Test
    void un_http_distinto_de_2xx_no_entrega_nada() {
        status = 503;
        var adapter = adaptador(Duration.ofSeconds(5));

        assertThatThrownBy(() -> adapter.download("PEP-MX", WatchlistKind.PEP, LocalDate.of(2026, 10, 30)))
                .isInstanceOf(WatchlistLoadFailedException.class)
                .hasMessageContaining("HTTP 503");
    }

    @Test
    void un_proveedor_que_no_contesta_se_corta_en_el_timeout() {
        demoraMs = 3_000;
        var adapter = adaptador(Duration.ofMillis(300));

        long inicio = System.nanoTime();
        assertThatThrownBy(() -> adapter.download("PEP-MX", WatchlistKind.PEP, LocalDate.of(2026, 10, 30)))
                .isInstanceOf(WatchlistLoadFailedException.class);
        // Rendimiento: se corta cerca del límite configurado, no a los 3 s del proveedor.
        assertThat((System.nanoTime() - inicio) / 1_000_000).isLessThan(1_500L);
    }

    @Test
    void una_lista_no_declarada_falla_con_su_nombre() {
        var adapter = adaptador(Duration.ofSeconds(5));
        assertThatThrownBy(() -> adapter.download("OFAC-SDN", WatchlistKind.SANCTIONS, LocalDate.of(2026, 10, 30)))
                .isInstanceOf(WatchlistLoadFailedException.class)
                .hasMessageContaining("OFAC-SDN");
    }

    @Test
    void sin_fuentes_o_sin_columna_de_nombre_no_arranca() {
        assertThatThrownBy(() -> new HttpGovernmentListAdapter(new GovernmentListProviderProperties(
                "http", "QeQ", null, null, Map.of())))
                .isInstanceOf(IllegalStateException.class);
        var sinNombre = new GovernmentListProviderProperties.Source("http://x/{date}", null, null, null, ",", "UTF-8",
                Map.of(CsvLayout.COL_CURP, "CURP"), List.of());
        assertThatThrownBy(() -> new HttpGovernmentListAdapter(new GovernmentListProviderProperties(
                "http", "QeQ", null, null, Map.of("PEP-MX", sinNombre))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
