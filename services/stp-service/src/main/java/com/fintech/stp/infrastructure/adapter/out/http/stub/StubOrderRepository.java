package com.fintech.stp.infrastructure.adapter.out.http.stub;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface StubOrderRepository extends JpaRepository<StubOrder, UUID> {

    List<StubOrder> findByEmpresaAndBusinessDateOrderByReceivedAt(String company, LocalDate businessDate);

    boolean existsByClaveRastreo(String claveRastreo);

    /** The mark of the first order of a payment that fails once (scenario .11). */
    Optional<StubOrder> findFirstByCuentaBeneficiarioAndMontoAndScenarioOrderByReceivedAt(String cuentaBeneficiario,
                                                                                         BigDecimal monto,
                                                                                         String scenario);
}
