package com.fintech.wallet;

import com.fintech.wallet.application.RequestDispositionCommand;
import com.fintech.wallet.application.port.out.WalletEventPublisher;
import com.fintech.wallet.application.port.out.WalletViewRepository;
import com.fintech.wallet.application.service.DispositionOrchestrationService;
import com.fintech.wallet.domain.WalletView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

/**
 * Wallet es un paso: no decide a nombre de quién sale el dinero, pero tampoco puede perderlo.
 *
 * <p>La colocación llega con la CLABE de la beneficiaria <b>y</b> su nombre y RFC. Si wallet
 * reenviara sólo la CLABE —como hacía— cartera completaba el destino con los datos de la cuenta, que
 * son los de la distribuidora.
 */
@ExtendWith(MockitoExtension.class)
class DispositionOrchestrationServiceTest {

    @Mock WalletViewRepository walletViewRepository;
    @Mock WalletEventPublisher eventPublisher;

    DispositionOrchestrationService service;

    private final UUID creditAccountId = UUID.randomUUID();
    private final UUID distributor     = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DispositionOrchestrationService(walletViewRepository, eventPublisher);
        WalletView line = WalletView.createFromActivation(creditAccountId, distributor,
                "DISTRIBUTOR_LINE", BigDecimal.ZERO, new BigDecimal("60000"));
        given(walletViewRepository.findByCreditAccountId(any())).willReturn(Optional.of(line));
    }

    private RequestDispositionCommand placement(UUID beneficiary, String name, String taxId) {
        return new RequestDispositionCommand(creditAccountId, distributor, new BigDecimal("1000"), null,
                beneficiary, "646180157000000004", name, taxId, 24);
    }

    @Test
    @DisplayName("la colocación se publica con el nombre y el RFC de la beneficiaria junto a su CLABE")
    void publicaElTitularDeLaClabe() {
        UUID beneficiary = UUID.randomUUID();

        service.request(placement(beneficiary, "ROSA ELENA IBARRA SALGADO", "IASR900612B0B"));

        then(eventPublisher).should().publishDispositionRequested(
                eq(creditAccountId), eq(distributor), any(), isNull(),
                eq(beneficiary), eq("646180157000000004"),
                eq("ROSA ELENA IBARRA SALGADO"), eq("IASR900612B0B"), eq(24));
    }

    @Test
    @DisplayName("varias colocaciones a la vez: cada evento lleva a SU beneficiaria")
    void concurrentes_cadaUnaConSuTitular() throws Exception {
        int colocaciones = 8;
        ExecutorService pool = Executors.newFixedThreadPool(colocaciones);
        CountDownLatch salida = new CountDownLatch(1);
        List<Future<?>> tareas = new ArrayList<>();
        List<UUID> beneficiarias = new ArrayList<>();
        for (int i = 0; i < colocaciones; i++) {
            UUID b = UUID.randomUUID();
            beneficiarias.add(b);
            String nombre = "BENEFICIARIA " + i;
            tareas.add(pool.submit(() -> {
                salida.await();
                service.request(placement(b, nombre, null));
                return null;
            }));
        }
        salida.countDown();
        for (Future<?> t : tareas) t.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        for (int i = 0; i < colocaciones; i++) {
            then(eventPublisher).should().publishDispositionRequested(
                    eq(creditAccountId), eq(distributor), any(), isNull(),
                    eq(beneficiarias.get(i)), eq("646180157000000004"),
                    eq("BENEFICIARIA " + i), isNull(), eq(24));
        }
    }
}
