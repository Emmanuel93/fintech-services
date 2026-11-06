package com.fintech.channelbackoffice.infrastructure.adapter.in.api;

import com.fintech.shared.trace.Correlation;
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

@Component
@Order(1)
public class MdcCorrelationFilter implements Filter {

    public static final String HEADER  = Correlation.HTTP_HEADER;
    public static final String MDC_KEY = Correlation.MDC_KEY;

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest  httpReq = (HttpServletRequest)  req;
        HttpServletResponse httpRes = (HttpServletResponse) res;

        // The platform filter (shared) already put the request's id in the log context; this one only keeps the
        // channel's contract when it runs alone (a slice test without the auto-configuration).
        String corrId = Correlation.current();
        if (corrId == null) {
            corrId = Correlation.clean(httpReq.getHeader(HEADER));
        }
        if (corrId == null) {
            corrId = Correlation.newId();
        }

        String previo = MDC.get(MDC_KEY);
        MDC.put(MDC_KEY, corrId);
        httpRes.setHeader(HEADER, corrId);
        try {
            chain.doFilter(req, res);
        } finally {
            if (previo == null) {
                MDC.remove(MDC_KEY);
            }
        }
    }
}
