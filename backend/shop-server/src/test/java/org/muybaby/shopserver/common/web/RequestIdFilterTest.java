package org.muybaby.shopserver.common.web;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RequestIdFilterTest {

    private static final String MDC_KEY = "requestId";

    @Test
    void keepsIncomingRequestId() throws Exception {
        RequestIdFilter filter = new RequestIdFilter();
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        request.addHeader(RequestIdFilter.HEADER_NAME, "req-123");
        doAnswer(invocation -> {
            assertThat(MDC.get(MDC_KEY)).isEqualTo("req-123");
            return null;
        }).when(chain).doFilter(request, response);

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(RequestIdFilter.HEADER_NAME)).isEqualTo("req-123");
        assertThat(MDC.get(MDC_KEY)).isNull();
        verify(chain).doFilter(request, response);
    }

    @Test
    void createsRequestIdWhenMissing() throws Exception {
        RequestIdFilter filter = new RequestIdFilter();
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        doAnswer(invocation -> {
            assertThat(MDC.get(MDC_KEY)).isNotBlank();
            return null;
        }).when(chain).doFilter(request, response);

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(RequestIdFilter.HEADER_NAME)).isNotBlank();
        assertThat(MDC.get(MDC_KEY)).isNull();
        verify(chain).doFilter(request, response);
    }
}
