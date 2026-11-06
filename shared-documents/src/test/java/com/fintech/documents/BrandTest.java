package com.fintech.documents;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BrandTest {

    @Test
    void aConfiguredColorReplacesItsRole_evenWhenItEqualsAnotherRolesDefault() {
        String jrxml = "<a c=\"#0D47A1\"/><b c=\"#00B894\"/><c c=\"#0d47a1\"/>";
        String out = Brand.applyPalette(jrxml, Brand.defaultRoles(), Map.of("primary", "#123456", "accent", "0d47a1"));
        assertThat(out).isEqualTo("<a c=\"#123456\"/><b c=\"#0D47A1\"/><c c=\"#123456\"/>");
        assertThat(Brand.applyPalette(jrxml, Brand.defaultRoles(), Map.of())).isEqualTo(jrxml);
    }

    @Test
    void anInvalidColorFailsLoudly() {
        assertThatThrownBy(() -> Brand.applyPalette("x", Brand.defaultRoles(), Map.of("primary", "azul")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("se espera #RRGGBB");
    }

    @Test
    void theLogotypeAndTheTypefaceArePackagedHere() {
        assertThat(BrandTest.class.getResource("/branding/kredius-logotipo-azul.png")).isNotNull();
        assertThat(BrandTest.class.getResource("/fonts/kredius-fonts.xml")).isNotNull();
        assertThat(BrandTest.class.getResource("/fonts/inter/Inter-Regular.ttf")).isNotNull();
        assertThat(BrandTest.class.getResource("/jasperreports_extension.properties")).isNotNull();
    }
}
