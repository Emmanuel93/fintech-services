package com.fintech.stp.application.port.in;

import com.fintech.stp.domain.KeyPurpose;
import com.fintech.stp.domain.StpCompany;
import com.fintech.stp.domain.StpCompanyKey;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Administración del catálogo multi-empresa. Rol ADMIN, vía el BFF de backoffice. */
public interface ManageCompanyUseCase {

    StpCompany registerCompany(String code, String stpEmpresa, Integer institucionOperante,
                               String trackingPrefix, String clabeBankCode, String clabePlazaCode,
                               String clabeClientPrefix);

    List<StpCompany> listCompanies();

    // `addOrderingAccount` se retiró en BK-07b: dar de alta una cuenta de la que sale dinero es una
    // decisión de tesorería, y vive en `banking` — que además la concilia. Una segunda puerta aquí
    // habría permitido registrar una cuenta ordenante que el ruteo no conoce y que nadie cuadra.

    /**
     * Da de alta material criptográfico. El {@code materialBase64} se cifra dentro de esta llamada y
     * jamás se escribe a disco ni a log (KY-04).
     *
     * @param materialBase64 PKCS#8 (SIGNING) o SPKI (VERIFICATION), en Base64
     */
    StpCompanyKey registerKey(UUID companyId, String alias, KeyPurpose purpose, String materialBase64,
                              Instant validFrom, Instant validTo, String createdBy);

    /** Devuelve metadatos, nunca material (KY-01). */
    List<StpCompanyKey> listKeys(UUID companyId);

    void revokeKey(UUID companyId, UUID keyId);
}
