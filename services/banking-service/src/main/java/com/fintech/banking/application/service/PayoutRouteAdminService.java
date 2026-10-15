package com.fintech.banking.application.service;

import com.fintech.banking.application.port.in.ManagePayoutRoutesUseCase;
import com.fintech.banking.application.port.out.BankAccountRepository;
import com.fintech.banking.application.port.out.PayoutRouteRepository;
import com.fintech.banking.domain.PayoutRoute;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
public class PayoutRouteAdminService implements ManagePayoutRoutesUseCase {

    private static final Logger log = LoggerFactory.getLogger(PayoutRouteAdminService.class);
    private static final int PRIORIDAD_POR_DEFECTO = 100;

    private final PayoutRouteRepository rutas;
    private final BankAccountRepository accounts;

    public PayoutRouteAdminService(PayoutRouteRepository rutas, BankAccountRepository accounts) {
        this.rutas   = rutas;
        this.accounts = accounts;
    }

    @Override
    @Transactional
    public PayoutRoute register(AltaDeRuta a) {
        // Apuntar una ruta a una cuenta inexistente se detecta al dar de alta la ruta, no la noche
        // en que un pago la use: la clave foránea también lo impediría, con un mensaje ilegible.
        accounts.findById(a.bankAccountId()).orElseThrow(
                () -> new NoSuchElementException("Sin cuenta bancaria " + a.bankAccountId()));

        PayoutRoute ruta = rutas.save(PayoutRoute.of(a.companyId(), a.rail(), a.provider(),
                a.bankAccountId(), a.minAmount(), a.maxAmount(),
                a.priority() != null ? a.priority() : PRIORIDAD_POR_DEFECTO));

        log.info("Ruta de pago dada de alta id={} empresa={} rail={} proveedor={} cuenta={}",
                ruta.getId(), ruta.getCompanyId(), ruta.getRail(), ruta.getProvider(),
                ruta.getBankAccountId());
        return ruta;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PayoutRoute> list() { return rutas.findAll(); }

    @Override
    @Transactional
    public PayoutRoute disable(UUID id) {
        PayoutRoute ruta = rutas.findById(id).orElseThrow(
                () -> new NoSuchElementException("Sin ruta de pago " + id));
        ruta.disable();
        log.warn("Ruta {} DESHABILITADA — deja de participar en la resolución", id);
        return rutas.save(ruta);
    }
}
