package com.fintech.identity.infrastructure.adapter.in.api.dto;

import com.fintech.identity.application.StaffSession;
import com.fintech.identity.domain.Channel;

public record StaffSessionResponse(
        String accessToken,
        String refreshToken,
        long expiresIn,
        Channel channel,
        StaffProfileResponse user
) {
    public static StaffSessionResponse from(StaffSession session) {
        return new StaffSessionResponse(
                session.tokens().accessToken(),
                session.tokens().refreshToken(),
                session.tokens().expiresIn(),
                Channel.BACKOFFICE,
                StaffProfileResponse.from(session.profile()));
    }
}
