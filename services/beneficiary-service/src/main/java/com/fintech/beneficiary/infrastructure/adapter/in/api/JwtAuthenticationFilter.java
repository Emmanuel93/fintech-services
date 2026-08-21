package com.fintech.beneficiary.infrastructure.adapter.in.api;

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
 * Header-trust: el gateway ya validó el JWT RS256 e inyectó {@code X-User-Id} y {@code X-Roles}.
 *
 * <p>Ojo con la ruta pública: el bloque {@code kyc.localhost} del gateway <b>nunca</b> inyecta
 * estos headers, así que una petición de la beneficiaria llega sin autenticación y la cadena la
 * deja pasar sólo porque {@code /api/v1/beneficiary/public/**} está en {@code permitAll}. Quien la
 * autoriza de verdad es el token de invitado, no este filtro.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String userId = request.getHeader("X-User-Id");
        if (StringUtils.hasText(userId)) {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(userId, null,
                            parseRoles(request.getHeader("X-Roles"))));
        }
        chain.doFilter(request, response);
    }

    private static List<SimpleGrantedAuthority> parseRoles(String roles) {
        if (!StringUtils.hasText(roles)) return List.of();
        return Arrays.stream(roles.split(",")).map(String::trim).filter(r -> !r.isEmpty())
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList();
    }
}
