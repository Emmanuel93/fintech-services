package com.fintech.channelmobile.infrastructure.adapter.in.api;

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
 * Popula el SecurityContext desde los headers inyectados por el gateway.
 * identity-service es el ÚNICO emisor de tokens en el ecosistema.
 * El gateway valida RS256 e inyecta X-User-Id y X-Roles antes de enrutar al BFF.
 * No se re-valida el token aquí — el gateway ya lo hizo.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String userId = request.getHeader("X-User-Id");

        if (StringUtils.hasText(userId)) {
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
