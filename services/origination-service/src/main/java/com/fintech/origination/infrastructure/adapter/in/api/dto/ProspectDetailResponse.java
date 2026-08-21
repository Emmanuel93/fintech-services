package com.fintech.origination.infrastructure.adapter.in.api.dto;

import com.fintech.origination.domain.Prospect;
import com.fintech.origination.domain.ProspectAddress;
import com.fintech.origination.domain.ProspectDocument;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Lo que capturó el prospecto, completo, para la mesa de análisis del backoffice.
 *
 * <p>A diferencia de {@link ProspectResponse} —que es el acuse de registro (id, curp,
 * teléfono, vigencia)— aquí va el expediente capturado: datos personales, domicilio,
 * consentimientos con su sello de tiempo y los documentos que subió. El analista compara
 * esto contra el buró y el score antes de decidir, así que se expone tal cual se guardó.
 */
public record ProspectDetailResponse(
        String prospectId,
        String prospectType,
        String status,
        String firstName,
        String lastName1,
        String lastName2,
        String curp,
        String rfc,
        LocalDate dateOfBirth,
        String gender,
        String stateOfBirth,
        String phone,
        String email,
        Address address,
        String channelType,
        boolean privacyNoticeAccepted,
        Instant privacyNoticeAcceptedAt,
        boolean circuloConsentAccepted,
        Instant circuloConsentAcceptedAt,
        List<Document> documents,
        Instant createdAt,
        Instant expiresAt) {

    public record Address(
            String street, String exteriorNumber, String interiorNumber,
            String neighborhood, String municipality, String city, String state,
            String postalCode, String country) {

        static Address from(ProspectAddress a) {
            if (a == null) return null;
            return new Address(a.getStreet(), a.getExteriorNumber(), a.getInteriorNumber(),
                    a.getNeighborhood(), a.getMunicipality(), a.getCity(), a.getState(),
                    a.getPostalCode(), a.getCountry());
        }
    }

    public record Document(String documentType, String documentRef,
                           String incomeProofType, Instant uploadedAt) {

        static Document from(ProspectDocument d) {
            return new Document(
                    d.getDocumentType() == null ? null : d.getDocumentType().name(),
                    d.getDocumentRef(),
                    d.getIncomeProofType() == null ? null : d.getIncomeProofType().name(),
                    d.getUploadedAt());
        }
    }

    public static ProspectDetailResponse from(Prospect p) {
        return new ProspectDetailResponse(
                p.getProspectId().toString(),
                p.getProspectType() == null ? null : p.getProspectType().name(),
                p.getStatus() == null ? null : p.getStatus().name(),
                p.getFirstName(),
                p.getLastName1(),
                p.getLastName2(),
                p.getCurp(),
                p.getRfc(),
                p.getDateOfBirth(),
                p.getGender() == null ? null : p.getGender().name(),
                p.getStateOfBirth(),
                p.getPhone(),
                p.getEmail(),
                Address.from(p.getAddress()),
                p.getChannelType() == null ? null : p.getChannelType().name(),
                p.isPrivacyNoticeAccepted(),
                p.getPrivacyNoticeAcceptedAt(),
                p.isCirculoConsentAccepted(),
                p.getCirculoConsentAcceptedAt(),
                p.getDocuments().stream().map(Document::from).toList(),
                p.getCreatedAt(),
                p.getExpiresAt());
    }
}
