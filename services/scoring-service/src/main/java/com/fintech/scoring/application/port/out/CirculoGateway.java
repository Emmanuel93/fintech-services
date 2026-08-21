package com.fintech.scoring.application.port.out;

import com.fintech.scoring.application.port.out.dto.CirculoQueryRequest;
import com.fintech.scoring.domain.CirculoReport;

import java.util.UUID;

public interface CirculoGateway {
    CirculoReport query(UUID reportId, UUID prefetchId, UUID prospectId, CirculoQueryRequest request);
}
