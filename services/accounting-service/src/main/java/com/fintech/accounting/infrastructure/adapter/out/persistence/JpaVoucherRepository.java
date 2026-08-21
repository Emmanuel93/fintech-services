package com.fintech.accounting.infrastructure.adapter.out.persistence;

import com.fintech.accounting.application.port.out.VoucherRepository;
import com.fintech.accounting.domain.Voucher;
import com.fintech.accounting.domain.VoucherType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JpaVoucherRepository extends JpaRepository<Voucher, UUID>, VoucherRepository {

    @Override
    Optional<Voucher> findBySourceEventId(String sourceEventId);

    @Override
    boolean existsBySourceEventId(String sourceEventId);

    @Override
    List<Voucher> findByCreditAccountId(UUID creditAccountId);

    /**
     * {@code IS NOT DISTINCT FROM} y no {@code =}: la sucursal puede ser nula —los créditos
     * anteriores al sellado— y {@code NULL = NULL} es NULL, así que con igualdad la serie de «Sin
     * sucursal» reiniciaría en 1 en cada póliza y la restricción única saltaría en la segunda.
     */
    @Query(value = """
            SELECT COALESCE(MAX(v.folio), 0) + 1
              FROM accounting.vouchers v
             WHERE v.voucher_type = :type
               AND v.org_unit_code IS NOT DISTINCT FROM :orgUnitCode
               AND v.period = :period
            """, nativeQuery = true)
    long nextFolioNative(@Param("type") String type,
                         @Param("orgUnitCode") String orgUnitCode,
                         @Param("period") String period);

    @Override
    default long nextFolio(VoucherType type, String orgUnitCode, String period) {
        return nextFolioNative(type.name(), orgUnitCode, period);
    }
}
