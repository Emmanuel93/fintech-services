package com.fintech.disbursement.infrastructure.adapter.in.api.dto;

import com.fintech.disbursement.application.port.out.DisbursementOrderRepository;

import java.util.List;

/** Una página de órdenes, con el total que la pantalla necesita para paginar sin adivinar. */
public record DisbursementPageResponse(
        List<DisbursementResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {

    public static DisbursementPageResponse from(DisbursementOrderRepository.OrderPage p) {
        return new DisbursementPageResponse(
                p.content().stream().map(DisbursementResponse::from).toList(),
                p.page(), p.size(), p.totalElements(), p.totalPages());
    }
}
