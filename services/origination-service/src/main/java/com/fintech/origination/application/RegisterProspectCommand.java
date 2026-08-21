package com.fintech.origination.application;

import com.fintech.origination.domain.ChannelType;
import com.fintech.origination.domain.Gender;
import com.fintech.origination.domain.ProspectDocument;
import com.fintech.origination.domain.ProspectType;

import java.time.LocalDate;
import java.util.List;

public record RegisterProspectCommand(
        ProspectType prospectType,
        String firstName,
        String lastName1,
        String lastName2,
        String curp,
        String rfc,
        LocalDate dateOfBirth,
        Gender gender,
        String stateOfBirth,
        String phone,
        String email,
        String street,
        String exteriorNumber,
        String interiorNumber,
        String neighborhood,
        String municipality,
        String city,
        String state,
        String postalCode,
        String country,
        ChannelType channelType,
        boolean privacyNoticeAccepted,
        boolean circuloConsentAccepted,
        List<ProspectDocument> documents,
        String username,
        String password,
        String correlationId
) {}
