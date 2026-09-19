package com.lanchat.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_ATTRIBUTE = "lanchatRequestId";
    public static final String REQUEST_ID_HEADER = "X-Request-ID";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String supplied = request.getHeader(REQUEST_ID_HEADER);
        String requestId = StringUtils.hasText(supplied)
                && supplied.matches("^[A-Za-z0-9:_-]{6,80}$")
                ? supplied
                : "req_" + UUID.randomUUID().toString().replace("-", "");
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        MDC.put("requestId", requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("requestId");
        }
    }
}
