package com.lanchat.common;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void propagatesValidRequestIdAndClearsLoggingContext() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.REQUEST_ID_HEADER, "req_client_123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            assertEquals("req_client_123", request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE));
            assertEquals("req_client_123", MDC.get("requestId"));
        });

        assertEquals("req_client_123", response.getHeader(RequestIdFilter.REQUEST_ID_HEADER));
        assertNull(MDC.get("requestId"));
    }

    @Test
    void replacesUnsafeRequestId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.REQUEST_ID_HEADER, "bad value with spaces");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> { });

        assertTrue(response.getHeader(RequestIdFilter.REQUEST_ID_HEADER).startsWith("req_"));
    }
}
