package com.fintech.statement.infrastructure.adapter.in.api;

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

/** La identidad que pone el BFF o el servicio que llama, en {@code X-User-Id} y {@code X-Roles}. */
@Component
public class HeaderAuthenticationFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String userId = request.getHeader("X-User-Id");
        if (StringUtils.hasText(userId)) {
            List<SimpleGrantedAuthority> roles = roles(request.getHeader("X-Roles"));
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(userId, null, roles));
        }
        chain.doFilter(request, response);
    }

    private static List<SimpleGrantedAuthority> roles(String header) {
        if (!StringUtils.hasText(header)) return List.of();
        return Arrays.stream(header.split(",")).map(String::trim).filter(r -> !r.isEmpty())
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList();
    }
}
