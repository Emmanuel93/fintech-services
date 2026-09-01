package com.fintech.creditproduct.infrastructure.adapter.in.api;

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
 * Popula el SecurityContext desde los headers que inyecta el gateway.
 *
 * <p>credit-product es un servicio de dominio interno: la identidad le llega ya validada por
 * {@code X-User-Id} y {@code X-Roles} en la cadena gateway (valida RS256) → BFF → dominio. El
 * gateway <b>limpia</b> esos headers si vienen del cliente y los reescribe desde el token, así que
 * dentro de la red no son falsificables.
 *
 * <p>Antes este filtro validaba un JWT propio contra un secreto configurable. La configuración
 * nunca le llegaba en el despliegue, de modo que <b>ninguna</b> escritura del catálogo era posible:
 * todo token —válido o no— caía en 401. Un producto no se podía crear, activar ni retirar desde el
 * backoffice. La prueba de integración no lo veía porque ella misma inyectaba el secreto.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String userId = request.getHeader("X-User-Id");

        if (StringUtils.hasText(userId)) {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(
                            userId, null, parseRoles(request.getHeader("X-Roles"))));
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
