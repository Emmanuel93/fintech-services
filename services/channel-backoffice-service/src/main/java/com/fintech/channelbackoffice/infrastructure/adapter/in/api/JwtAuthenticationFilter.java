package com.fintech.channelbackoffice.infrastructure.adapter.in.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Popula el SecurityContext desde los headers que inyecta el gateway tras validar el JWT RS256:
 * {@code X-User-Id} (aquí es el staffUserId), {@code X-Roles} y {@code X-Channel}.
 *
 * <p>No se revalida el token: identity-service es el único emisor y el gateway ya comprobó firma,
 * expiración, issuer y canal. Lo que sí se hace es rechazar cualquier request que llegue con un
 * canal distinto de BACKOFFICE — defensa en profundidad por si alguien expone este servicio sin
 * gateway delante.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BACKOFFICE_CHANNEL = "BACKOFFICE";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String userId  = request.getHeader("X-User-Id");
        String channel = request.getHeader("X-Channel");

        if (StringUtils.hasText(userId)) {
            if (channel != null && !BACKOFFICE_CHANNEL.equals(channel)) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "channel_not_allowed");
                return;
            }
            List<SimpleGrantedAuthority> authorities = parseRoles(request.getHeader("X-Roles"));
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(userId, null, authorities));
        }

        chain.doFilter(request, response);
    }

    private static List<SimpleGrantedAuthority> parseRoles(String roles) {
        if (!StringUtils.hasText(roles)) return List.of();
        return Arrays.stream(roles.split(","))
                .map(String::trim)
                .filter(r -> !r.isEmpty())
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                .toList();
    }
}
