package com.fintech.beneficiary.publickyc;

import com.fintech.beneficiary.application.KycData;
import com.fintech.beneficiary.application.service.KycDataValidator;
import com.fintech.beneficiary.domain.Curp;
import com.fintech.beneficiary.domain.PublicKycException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KycDataValidatorTest {

    private final KycDataValidator validator = new KycDataValidator(
            Clock.fixed(Instant.parse("2026-10-29T15:00:00Z"), ZoneOffset.UTC));

    private static KycData with(String curp, String rfc, String clabe, String dob) {
        KycData d = KycFixtures.data();
        return new KycData(d.firstName(), d.lastName1(), d.lastName2(), curp, rfc, dob,
                d.occupation(), d.monthlyIncome(), d.street(), d.exteriorNumber(), d.neighborhood(),
                d.municipality(), d.city(), d.state(), d.postalCode(), clabe, d.email());
    }

    private static PublicKycException.InvalidData invalid(Runnable r) {
        try {
            r.run();
        } catch (PublicKycException.InvalidData e) {
            return e;
        }
        throw new AssertionError("se esperaba DATOS_INVALIDOS");
    }

    @Test
    @DisplayName("datos completos y válidos pasan, normalizados")
    void validDataPasses() {
        KycData raw = with(KycFixtures.CURP.toLowerCase(), " cohm900612ab1 ", KycFixtures.CLABE, "1990-06-12");
        KycData clean = validator.validate(raw);
        assertThat(clean.curp()).isEqualTo(KycFixtures.CURP);
        assertThat(clean.rfc()).isEqualTo("COHM900612AB1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"COHM900612XDFRRR04", "COHM9006MDFRRR04", "C0HM900612MDFRRR04", "COHM900612MZZRRR04"})
    @DisplayName("CURP mal formada o con entidad inexistente: error en `curp`")
    void badCurp(String curp) {
        var e = invalid(() -> validator.validate(with(curp, "COHM900612AB1", KycFixtures.CLABE, "1990-06-12")));
        assertThat(e.fields()).containsKey("curp");
        assertThat(e.wireCode()).isEqualTo("DATOS_INVALIDOS");
    }

    @Test
    @DisplayName("una CLABE de 18 dígitos con el verificador mal NO pasa — el error de captura más común")
    void clabeCheckDigitIsEnforced() {
        String good = KycFixtures.CLABE;
        char last = good.charAt(17);
        String bad = good.substring(0, 17) + (last == '9' ? '0' : (char) (last + 1));
        var e = invalid(() -> validator.validate(with(KycFixtures.CURP, "COHM900612AB1", bad, "1990-06-12")));
        assertThat(e.fields().get("clabe")).contains("no es válida");
    }

    @Test
    @DisplayName("una CLABE que no es de 18 dígitos se rechaza por forma")
    void clabeShape() {
        var e = invalid(() -> validator.validate(with(KycFixtures.CURP, "COHM900612AB1", "12345", "1990-06-12")));
        assertThat(e.fields().get("clabe")).contains("18 dígitos");
    }

    @Test
    @DisplayName("RFC de 12 posiciones (persona moral) no es el de una persona física")
    void rfcMustBe13() {
        var e = invalid(() -> validator.validate(with(KycFixtures.CURP, "COH900612AB1", KycFixtures.CLABE, "1990-06-12")));
        assertThat(e.fields()).containsKey("rfc");
    }

    @Test
    @DisplayName("menor de edad o fecha ilegible: error en `dateOfBirth`")
    void dateOfBirth() {
        assertThat(invalid(() -> validator.validate(with(KycFixtures.CURP, "COHM900612AB1", KycFixtures.CLABE, "2012-01-01")))
                .fields().get("dateOfBirth")).contains("mayor de edad");
        assertThat(invalid(() -> validator.validate(with(KycFixtures.CURP, "COHM900612AB1", KycFixtures.CLABE, "12/06/1990")))
                .fields().get("dateOfBirth")).contains("AAAA-MM-DD");
    }

    @Test
    @DisplayName("todos los errores salen a la vez, no uno por envío")
    void allErrorsAtOnce() {
        KycData empty = new KycData(null, " ", null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, "no-es-correo");
        var e = invalid(() -> validator.validate(empty));
        assertThat(e.fields()).containsKeys("firstName", "lastName1", "curp", "rfc", "dateOfBirth",
                "occupation", "monthlyIncome", "street", "exteriorNumber", "neighborhood",
                "municipality", "city", "state", "postalCode", "clabe", "email");
        assertThat(e.fields()).doesNotContainKey("lastName2");
    }

    @Test
    @DisplayName("ingreso en cero no es ingreso")
    void incomeMustBePositive() {
        KycData d = KycFixtures.data();
        KycData zero = new KycData(d.firstName(), d.lastName1(), d.lastName2(), d.curp(), d.rfc(),
                d.dateOfBirth(), d.occupation(), BigDecimal.ZERO, d.street(), d.exteriorNumber(),
                d.neighborhood(), d.municipality(), d.city(), d.state(), d.postalCode(), d.clabe(), null);
        assertThat(invalid(() -> validator.validate(zero)).fields()).containsKey("monthlyIncome");
    }

    @Test
    @DisplayName("de la CURP salen el sexo y la entidad de nacimiento que pide origination")
    void curpFacts() {
        assertThat(Curp.genderOf(KycFixtures.CURP)).isEqualTo("FEMALE");
        assertThat(Curp.genderOf("COHM900612HDFRRR04")).isEqualTo("MALE");
        assertThat(Curp.stateOfBirthOf(KycFixtures.CURP)).contains("Ciudad de México");
        assertThat(Curp.stateOfBirthOf("COHM900612HNERRR04")).contains("Nacido en el extranjero");
        assertThat(Curp.stateOfBirthOf("COHM900612HJCRRR04")).contains("Jalisco");
    }
}
