package com.fintech.compliance;

import com.fintech.compliance.application.service.WatchlistLoadService;
import com.fintech.compliance.domain.WatchlistKind;
import com.fintech.compliance.domain.WatchlistVersionStatus;
import com.fintech.compliance.infrastructure.config.WatchlistSources;
import com.fintech.compliance.infrastructure.job.WatchlistSyncJob;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * La sincronización de listas: que las declaradas se carguen, que una que falla no tumbe a las
 * otras, y que los interruptores apaguen lo que dicen apagar.
 */
class WatchlistSyncJobTest {

    private static final WatchlistSources DOS = new WatchlistSources(List.of(
            new WatchlistSources.Source("OFAC-SDN", WatchlistKind.SANCTIONS),
            new WatchlistSources.Source("PEP-MX", WatchlistKind.PEP)));

    private static WatchlistLoadService.LoadResult activa(String code) {
        return new WatchlistLoadService.LoadResult(code, WatchlistVersionStatus.ACTIVE, "h", 10, 9, 1, null);
    }

    @Test
    @DisplayName("carga cada lista declarada con la publicación de hoy")
    void loadsEveryDeclaredList() {
        WatchlistLoadService loader = mock(WatchlistLoadService.class);
        when(loader.load(any(), any(), any())).thenAnswer(inv -> activa(inv.getArgument(0)));

        var resultados = new WatchlistSyncJob(loader, DOS, true, true).syncAll();

        assertThat(resultados).extracting(WatchlistLoadService.LoadResult::sourceCode)
                .containsExactly("OFAC-SDN", "PEP-MX");
        verify(loader).load(eq("OFAC-SDN"), eq(WatchlistKind.SANCTIONS), any(LocalDate.class));
        verify(loader).load(eq("PEP-MX"), eq(WatchlistKind.PEP), any(LocalDate.class));
    }

    @Test
    @DisplayName("una lista que no baja no impide cargar las demás")
    void aFailingListDoesNotStopTheOthers() {
        WatchlistLoadService loader = mock(WatchlistLoadService.class);
        when(loader.load(eq("OFAC-SDN"), any(), any())).thenThrow(new IllegalStateException("publicador caído"));
        when(loader.load(eq("PEP-MX"), any(), any())).thenReturn(activa("PEP-MX"));

        var resultados = new WatchlistSyncJob(loader, DOS, true, true).syncAll();

        assertThat(resultados).extracting(WatchlistLoadService.LoadResult::sourceCode).containsExactly("PEP-MX");
    }

    @Test
    @DisplayName("apagada no carga nada, ni al arrancar ni de madrugada")
    void disabledLoadsNothing() {
        WatchlistLoadService loader = mock(WatchlistLoadService.class);
        var job = new WatchlistSyncJob(loader, DOS, false, true);

        job.atStartup();
        job.nightly();

        verifyNoInteractions(loader);
    }

    @Test
    @DisplayName("sin carga de arranque, la de madrugada sigue corriendo")
    void withoutStartupTheNightlyStillRuns() {
        WatchlistLoadService loader = mock(WatchlistLoadService.class);
        when(loader.load(any(), any(), any())).thenAnswer(inv -> activa(inv.getArgument(0)));
        var job = new WatchlistSyncJob(loader, DOS, true, false);

        job.atStartup();
        verifyNoInteractions(loader);
        job.nightly();
        verify(loader, times(2)).load(any(), any(), any());
    }

    @Test
    @DisplayName("sin listas declaradas no inventa ninguna")
    void withoutDeclaredListsNothingHappens() {
        WatchlistLoadService loader = mock(WatchlistLoadService.class);

        assertThat(new WatchlistSyncJob(loader, new WatchlistSources(null), true, true).syncAll()).isEmpty();
        verifyNoInteractions(loader);
    }

    @Test
    @DisplayName("disparos simultáneos —arranque, madrugada, endpoint— no se estorban entre sí")
    void concurrentTriggersAllComplete() throws Exception {
        WatchlistLoadService loader = mock(WatchlistLoadService.class);
        when(loader.load(any(), any(), any())).thenAnswer(inv -> activa(inv.getArgument(0)));
        var job = new WatchlistSyncJob(loader, DOS, true, true);

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch salida = new CountDownLatch(1);
        List<Future<List<WatchlistLoadService.LoadResult>>> tareas = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tareas.add(pool.submit(() -> { salida.await(); return job.syncAll(); }));
        }
        salida.countDown();
        for (var t : tareas) assertThat(t.get(10, TimeUnit.SECONDS)).hasSize(2);
        pool.shutdown();
        // La idempotencia frente a cargas repetidas la da el hash en WatchlistLoadService; aquí se
        // comprueba que el job no comparte estado mutable entre disparos.
        verify(loader, times(8)).load(any(), any(), any());
    }
}
