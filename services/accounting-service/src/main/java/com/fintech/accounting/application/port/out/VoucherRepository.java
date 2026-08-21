package com.fintech.accounting.application.port.out;

import com.fintech.accounting.domain.Voucher;
import com.fintech.accounting.domain.VoucherType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VoucherRepository {

    Voucher save(Voucher voucher);

    Optional<Voucher> findById(UUID voucherId);

    Optional<Voucher> findBySourceEventId(String sourceEventId);

    boolean existsBySourceEventId(String sourceEventId);

    /**
     * El siguiente folio de la serie (tipo, sucursal, período).
     *
     * <p>Por serie y no global: así la sucursal Culiacán tiene sus folios 1..N sin huecos causados
     * por el movimiento de otra sucursal, que es lo primero que revisa quien audita una serie.
     */
    long nextFolio(VoucherType type, String orgUnitCode, String period);

    List<Voucher> findByCreditAccountId(UUID creditAccountId);
}
