package com.fintech.beneficiary.domain;

import com.fintech.shared.exception.DomainException;

import java.util.UUID;

/** A distributor acting on a client she never placed, or on a mark she did not put. */
public class ClientNotYoursException extends DomainException {

    public ClientNotYoursException(UUID beneficiaryPartyId, String what) {
        super("CLIENT_NOT_YOURS", "No puedes " + what + " del cliente " + beneficiaryPartyId + ": no es tu cliente o la marca no es tuya");
    }
}
