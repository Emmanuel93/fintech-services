package com.fintech.identity.application.port.out;

import com.fintech.identity.domain.Channel;

import java.util.List;

public record TokenClaims(
        String subject,
        String jti,
        List<String> roles,
        String deviceId,

        /** Canal que emitió el token; los tokens previos al claim se leen como MOBILE. */
        Channel channel
) {}
