package com.fintech.origination.infrastructure.adapter.in.api.dto;

import com.fintech.origination.domain.ChannelType;
import com.fintech.origination.domain.Gender;
import com.fintech.origination.domain.ProspectType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.LocalDate;
import java.util.List;

public record RegisterProspectRequest(

        ProspectType prospectType,

        @NotBlank @Size(max = 100)
        String firstName,

        @NotBlank @Size(max = 100)
        String lastName1,

        @Size(max = 100)
        String lastName2,

        @NotBlank @Pattern(regexp = "^[A-Z]{4}\\d{6}[HM][A-Z]{5}[A-Z0-9]\\d$",
                           message = "Invalid CURP format")
        String curp,

        @Pattern(regexp = "^[A-Z&Ñ]{3,4}\\d{6}[A-Z0-9]{3}$",
                 message = "Invalid RFC format")
        String rfc,

        @NotNull @Past
        LocalDate dateOfBirth,

        @NotNull
        Gender gender,

        @NotBlank @Size(max = 100)
        String stateOfBirth,

        @NotBlank @Pattern(regexp = "^\\+?[1-9]\\d{9,14}$", message = "Invalid phone format")
        String phone,

        @Email @Size(max = 254)
        String email,

        @NotBlank @Size(max = 200)
        String street,

        @NotBlank @Size(max = 20)
        String exteriorNumber,

        @Size(max = 20)
        String interiorNumber,

        @NotBlank @Size(max = 100)
        String neighborhood,

        @Size(max = 100)
        String municipality,

        @NotBlank @Size(max = 100)
        String city,

        @NotBlank @Size(max = 100)
        String state,

        @NotBlank @Pattern(regexp = "^\\d{5}$", message = "Postal code must be 5 digits")
        String postalCode,

        @Size(max = 2)
        String country,

        @NotNull
        ChannelType channelType,

        @AssertTrue(message = "Privacy notice must be accepted")
        boolean privacyNoticeAccepted,

        boolean circuloConsentAccepted,

        @Valid
        List<ProspectDocumentRequest> documents,

        @NotBlank(message = "Username is required")
        @Size(min = 4, max = 50, message = "Username must be between 4 and 50 characters")
        @Pattern(regexp = "^[a-zA-Z0-9._-]+$", message = "Username may only contain letters, digits, dots, underscores and hyphens")
        String username,

        @NotBlank(message = "Password is required")
        @Size(min = 8, max = 100, message = "Password must be at least 8 characters")
        String password
) {}
