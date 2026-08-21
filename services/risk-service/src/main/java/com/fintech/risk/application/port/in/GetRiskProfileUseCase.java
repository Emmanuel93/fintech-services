package com.fintech.risk.application.port.in;

import com.fintech.risk.domain.Ifrs9Stage;
import com.fintech.risk.domain.RiskProfile;
import com.fintech.risk.domain.RiskProfileStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface GetRiskProfileUseCase {
    RiskProfile getByCreditAccountId(UUID creditAccountId);
    List<RiskProfile> getByPartyId(UUID obligorPartyId);

    /** Listado paginado filtrable (partyId opcional) para el backoffice. */
    Page<RiskProfile> search(UUID partyId, String productType, Ifrs9Stage stage,
                             RiskProfileStatus status, Pageable pageable);

    /** Hidratación por lote por id de cuenta (evita N+1 al pintar una tabla). */
    List<RiskProfile> findByCreditAccountIds(Collection<UUID> creditAccountIds);
}
