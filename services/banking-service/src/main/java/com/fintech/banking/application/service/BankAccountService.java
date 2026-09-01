package com.fintech.banking.application.service;

import com.fintech.banking.application.BankingProperties;
import com.fintech.banking.application.port.in.ManageBankAccountsUseCase;
import com.fintech.banking.application.port.out.BankAccountRepository;
import com.fintech.banking.domain.BankAccount;
import com.fintech.banking.domain.Clabe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
public class BankAccountService implements ManageBankAccountsUseCase {

    private static final Logger log = LoggerFactory.getLogger(BankAccountService.class);

    /** Los defaults que declara el catálogo contable (`accounting:011-bank-suspense`). */
    private static final String PUENTE_ABONOS = "2109";
    private static final String PUENTE_CARGOS = "1109";
    private static final String BANCOS        = "1101";

    private final BankAccountRepository repo;
    private final BankingProperties props;

    public BankAccountService(BankAccountRepository repo, BankingProperties props) {
        this.repo  = repo;
        this.props = props;
    }

    @Override
    @Transactional
    public BankAccount alta(AltaDeCuenta a) {
        // La CLABE se valida ANTES de tocar la base: el dígito verificador atrapa el error de
        // captura en el único momento en que corregirlo es gratis.
        Clabe clabe = Clabe.of(a.clabe());

        // La unicidad la impone `uq_bank_accounts_clabe`, pero rebotar aquí da un motivo legible
        // en vez de una violación de restricción.
        repo.findByClabe(clabe.valor()).ifPresent(existente -> {
            throw new IllegalStateException(
                    "La CLABE " + clabe.enmascarada() + " ya está dada de alta: " + existente.getId());
        });

        BankAccount cuenta = BankAccount.alta(
                a.companyId(),
                a.institutionCode() != null ? a.institutionCode() : clabe.institucion(),
                a.institutionName(),
                clabe,
                a.holderName(),
                a.taxId(),
                a.currency() != null ? a.currency() : props.baseCurrency(),
                a.ledgerAccount()         != null ? a.ledgerAccount()         : BANCOS,
                a.suspenseCreditAccount() != null ? a.suspenseCreditAccount() : PUENTE_ABONOS,
                a.suspenseDebitAccount()  != null ? a.suspenseDebitAccount()  : PUENTE_CARGOS,
                a.providerClientRef());

        BankAccount guardada = repo.save(cuenta);
        // La CLABE nunca entra completa a la bitácora.
        log.info("Cuenta propia dada de alta id={} institución={} clabe={}",
                guardada.getId(), guardada.getInstitutionName(), clabe.enmascarada());
        return guardada;
    }

    @Override
    @Transactional(readOnly = true)
    public BankAccount consultar(UUID id) {
        return repo.findById(id).orElseThrow(
                () -> new NoSuchElementException("Sin cuenta bancaria " + id));
    }

    @Override
    @Transactional(readOnly = true)
    public List<BankAccount> listar(UUID companyId) {
        return companyId == null ? repo.findAll() : repo.findByCompany(companyId);
    }

    @Override
    @Transactional
    public BankAccount suspender(UUID id) {
        BankAccount c = consultar(id);
        c.suspender();
        log.warn("Cuenta {} SUSPENDIDA — deja de participar en el ruteo", id);
        return repo.save(c);
    }

    @Override
    @Transactional
    public BankAccount reactivar(UUID id) {
        BankAccount c = consultar(id);
        c.reactivar();
        return repo.save(c);
    }
}
