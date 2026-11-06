package com.fintech.stp.infrastructure.adapter.out.http.stub;

import org.springframework.data.jpa.repository.JpaRepository;

import java.math.BigDecimal;

interface StubScenarioAmountRepository extends JpaRepository<StubScenarioAmount, BigDecimal> {}
