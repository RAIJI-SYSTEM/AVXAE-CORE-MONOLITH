package com.raiji.web.audit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.util.ContentCachingRequestWrapper;

import static org.assertj.core.api.Assertions.assertThat;

class AuditRequestCachingFilterTest {

    private final AuditRequestCachingFilter filter = new AuditRequestCachingFilter();

    @Test
    void wrapsJsonWriteRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/rides");
        request.setContentType("application/json");
        request.setContent("{\"x\":1}".getBytes());
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isInstanceOf(ContentCachingRequestWrapper.class);
    }

    @Test
    void doesNotWrapReadRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/rides");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void doesNotWrapMultipartOrLargeBody() throws Exception {
        MockHttpServletRequest multipart = new MockHttpServletRequest("POST", "/api/rides");
        multipart.setContentType("multipart/form-data");
        multipart.setContent(new byte[]{1});
        MockFilterChain first = new MockFilterChain();
        filter.doFilter(multipart, new MockHttpServletResponse(), first);
        assertThat(first.getRequest()).isSameAs(multipart);

        MockHttpServletRequest large = new MockHttpServletRequest("POST", "/api/rides");
        large.setContentType("application/json");
        large.setContent(new byte[200 * 1024]);
        MockFilterChain second = new MockFilterChain();
        filter.doFilter(large, new MockHttpServletResponse(), second);
        assertThat(second.getRequest()).isSameAs(large);
    }
}
