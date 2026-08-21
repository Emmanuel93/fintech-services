package com.fintech.identity.infrastructure.adapter.in.api;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(1)
public class MdcCorrelationFilter implements Filter {

    static final String HEADER  = "X-Correlation-Id";
    static final String MDC_KEY = "corrId";

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest  httpReq = (HttpServletRequest)  req;
        HttpServletResponse httpRes = (HttpServletResponse) res;

        String corrId = httpReq.getHeader(HEADER);
        if (corrId == null || corrId.isBlank()) {
            corrId = UUID.randomUUID().toString().substring(0, 8);
        }

        MDC.put(MDC_KEY, corrId);
        httpRes.setHeader(HEADER, corrId);
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
