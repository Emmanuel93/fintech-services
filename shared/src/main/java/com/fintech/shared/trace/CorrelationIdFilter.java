package com.fintech.shared.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Every request runs under a correlation id: the caller's ({@value Correlation#HTTP_HEADER}) when it sends a
 * usable one, a new one otherwise. It is put in the log context for the whole request and returned in the
 * response, so whoever called can quote it.
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = Correlation.clean(request.getHeader(Correlation.HTTP_HEADER));
        if (id == null) {
            id = Correlation.newId();
        }
        String previous = MDC.get(Correlation.MDC_KEY);
        MDC.put(Correlation.MDC_KEY, id);
        response.setHeader(Correlation.HTTP_HEADER, id);
        try {
            chain.doFilter(request, response);
        } finally {
            if (previous == null) {
                MDC.remove(Correlation.MDC_KEY);
            } else {
                MDC.put(Correlation.MDC_KEY, previous);
            }
        }
    }
}
