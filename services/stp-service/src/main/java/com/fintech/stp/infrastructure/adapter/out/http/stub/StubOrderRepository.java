package com.fintech.stp.infrastructure.adapter.out.http.stub;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

interface StubOrderRepository extends JpaRepository<StubOrder, UUID> {

    List<StubOrder> findByEmpresaAndBusinessDateOrderByReceivedAt(String empresa, LocalDate businessDate);

    boolean existsByClaveRastreo(String claveRastreo);
}
