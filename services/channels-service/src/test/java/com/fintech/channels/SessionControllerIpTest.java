package com.fintech.channels;

import com.fintech.channels.infrastructure.adapter.in.api.ClientIpExtractor;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * Verifica la extracción de IP del cliente — lógica crítica para detección de fraude,
 * cumplimiento regulatorio (LFPIORPI) y geolocalización. Se extrae siempre del lado
 * servidor para prevenir spoofing.
 */
@ExtendWith(MockitoExtension.class)
class SessionControllerIpTest {

    @Mock HttpServletRequest request;

    @Test
    void extract_usesFirstIpFromXForwardedFor() {
        given(request.getHeader("X-Forwarded-For")).willReturn("203.0.113.42, 10.0.0.1, 172.16.0.1");
        assertThat(ClientIpExtractor.extract(request)).isEqualTo("203.0.113.42");
    }

    @Test
    void extract_fallsBackToXRealIp_whenForwardedForAbsent() {
        given(request.getHeader("X-Forwarded-For")).willReturn(null);
        given(request.getHeader("X-Real-IP")).willReturn("198.51.100.7");
        assertThat(ClientIpExtractor.extract(request)).isEqualTo("198.51.100.7");
    }

    @Test
    void extract_fallsBackToRemoteAddr_whenBothHeadersAbsent() {
        given(request.getHeader("X-Forwarded-For")).willReturn(null);
        given(request.getHeader("X-Real-IP")).willReturn(null);
        given(request.getRemoteAddr()).willReturn("127.0.0.1");
        assertThat(ClientIpExtractor.extract(request)).isEqualTo("127.0.0.1");
    }

    @Test
    void extract_handlesIpv6Address() {
        given(request.getHeader("X-Forwarded-For")).willReturn("2001:db8::1, 10.0.0.1");
        assertThat(ClientIpExtractor.extract(request)).isEqualTo("2001:db8::1");
    }

    @Test
    void extract_trimsWhitespaceAroundIp() {
        given(request.getHeader("X-Forwarded-For")).willReturn("  203.0.113.10  , 10.0.0.1");
        assertThat(ClientIpExtractor.extract(request)).isEqualTo("203.0.113.10");
    }

    @Test
    void extract_handlesSingleIpInXForwardedFor() {
        given(request.getHeader("X-Forwarded-For")).willReturn("198.51.100.99");
        assertThat(ClientIpExtractor.extract(request)).isEqualTo("198.51.100.99");
    }

    @Test
    void extract_ignoresBlankXForwardedFor_fallsBackToXRealIp() {
        given(request.getHeader("X-Forwarded-For")).willReturn("   ");
        given(request.getHeader("X-Real-IP")).willReturn("10.10.10.10");
        assertThat(ClientIpExtractor.extract(request)).isEqualTo("10.10.10.10");
    }
}
