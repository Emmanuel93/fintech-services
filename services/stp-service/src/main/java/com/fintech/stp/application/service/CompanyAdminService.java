package com.fintech.stp.application.service;

import com.fintech.stp.application.port.in.ManageCompanyUseCase;
import com.fintech.stp.application.port.out.KeyMaterialCipher;
import com.fintech.stp.application.port.out.SigningKeyProvider;
import com.fintech.stp.application.port.out.StpCompanyKeyRepository;
import com.fintech.stp.application.port.out.StpCompanyRepository;
import com.fintech.stp.domain.CompanyNotFoundException;
import com.fintech.stp.domain.KeyPurpose;
import com.fintech.stp.domain.StpCompany;
import com.fintech.stp.domain.StpCompanyKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Alta y rotación del catálogo multi-empresa.
 *
 * <p>KY-04: el material privado se cifra dentro de esta llamada y no se escribe a disco ni a log.
 * Lo que entra por API es una cadena Base64 que sale de aquí ya envuelta.
 */
@Service
@Transactional
public class CompanyAdminService implements ManageCompanyUseCase {

    private static final Logger log = LoggerFactory.getLogger(CompanyAdminService.class);

    private final StpCompanyRepository companyRepository;
    private final StpCompanyKeyRepository keyRepository;
    private final KeyMaterialCipher cipher;
    private final SigningKeyProvider signingKeyProvider;

    public CompanyAdminService(StpCompanyRepository companyRepository,
                               StpCompanyKeyRepository keyRepository,
                               KeyMaterialCipher cipher,
                               SigningKeyProvider signingKeyProvider) {
        this.companyRepository = companyRepository;
        this.keyRepository = keyRepository;
        this.cipher = cipher;
        this.signingKeyProvider = signingKeyProvider;
    }

    @Override
    public StpCompany registerCompany(String code, String stpEmpresa, Integer institucionOperante,
                                      String trackingPrefix, String clabeBankCode, String clabePlazaCode,
                                      String clabeClientPrefix) {
        StpCompany company = companyRepository.save(StpCompany.create(
                code, stpEmpresa, institucionOperante, trackingPrefix,
                clabeBankCode, clabePlazaCode, clabeClientPrefix));
        log.info("STP company registered companyId={} code={} trackingPrefix={}",
                company.getCompanyId(), code, trackingPrefix);
        return company;
    }

    @Override
    @Transactional(readOnly = true)
    public List<StpCompany> listCompanies() {
        return companyRepository.findAll();
    }

    // `addOrderingAccount` se retiró en BK-07b. Dar de alta una cuenta de la que sale dinero es
    // una decisión de TESORERÍA, no del conector: vive en `banking`, que además la concilia. Dejar
    // aquí una segunda puerta habría permitido registrar una cuenta ordenante que el ruteo no
    // conoce — y que por tanto nadie cuadra.

    @Override
    public StpCompanyKey registerKey(UUID companyId, String alias, KeyPurpose purpose, String materialBase64,
                                     Instant validFrom, Instant validTo, String createdBy) {
        requireCompany(companyId);

        // Retirar la activa anterior antes de insertar: el índice único parcial lo exige.
        // saveAndFlush y no save: Hibernate ordena los INSERT antes que los UPDATE dentro de la
        // misma transacción, así que sin flush explícito el alta de la llave nueva entra mientras la
        // anterior sigue ACTIVE y la rotación falla siempre.
        keyRepository.findActiveByCompanyIdAndPurpose(companyId, purpose).ifPresent(previous -> {
            previous.retire();
            keyRepository.saveAndFlush(previous);
            log.info("Previous {} key retired companyId={} keyId={}", purpose, companyId, previous.getKeyId());
        });

        StpCompanyKey key = purpose == KeyPurpose.SIGNING
                ? cipher.wrapSigningKey(companyId, alias, materialBase64, validFrom, validTo, createdBy)
                : cipher.storeVerificationKey(companyId, alias, materialBase64, validFrom, validTo, createdBy);

        StpCompanyKey saved = keyRepository.save(key);
        signingKeyProvider.evict(companyId);

        // KY-01: se registra la huella, jamás el material.
        log.info("STP key registered companyId={} purpose={} keyId={} fingerprint={} validTo={}",
                companyId, purpose, saved.getKeyId(), saved.getFingerprintSha256(), validTo);
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public List<StpCompanyKey> listKeys(UUID companyId) {
        return keyRepository.findByCompanyId(companyId);
    }

    @Override
    public void revokeKey(UUID companyId, UUID keyId) {
        StpCompanyKey key = keyRepository.findById(keyId)
                .filter(k -> k.getCompanyId().equals(companyId))
                .orElseThrow(() -> new CompanyNotFoundException(
                        "No existe la llave " + keyId + " para la empresa " + companyId));
        key.revoke();
        keyRepository.save(key);
        signingKeyProvider.evict(companyId);
        log.warn("STP key REVOKED companyId={} keyId={} fingerprint={}",
                companyId, keyId, key.getFingerprintSha256());
    }

    private StpCompany requireCompany(UUID companyId) {
        return companyRepository.findById(companyId)
                .orElseThrow(() -> new CompanyNotFoundException("No existe la empresa " + companyId));
    }
}
