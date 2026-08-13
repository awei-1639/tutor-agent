package com.tutor.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class RequestTraceFilterTest {
    private final RequestTraceFilter filter = new RequestTraceFilter();

    @Test
    void propagatesSafeCallerTraceIdAndClearsMdc() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestTraceFilter.HEADER, "request_trace-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, assertingChain("request_trace-123"));

        assertThat(response.getHeader(RequestTraceFilter.HEADER)).isEqualTo("request_trace-123");
        assertThat(MDC.get(RequestTraceFilter.MDC_KEY)).isNull();
    }

    @Test
    void replacesUnsafeCallerTraceId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestTraceFilter.HEADER, "unsafe value with spaces");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, assertingChain(response));

        assertThat(response.getHeader(RequestTraceFilter.HEADER)).matches("[A-Za-z0-9]{32}");
    }

    private static FilterChain assertingChain(String expectedTraceId) {
        return (request, response) -> assertThat(MDC.get(RequestTraceFilter.MDC_KEY)).isEqualTo(expectedTraceId);
    }

    private static FilterChain assertingChain(MockHttpServletResponse response) {
        return (request, ignoredResponse) -> assertThat(response.getHeader(RequestTraceFilter.HEADER)).matches("[A-Za-z0-9]{32}");
    }
}
