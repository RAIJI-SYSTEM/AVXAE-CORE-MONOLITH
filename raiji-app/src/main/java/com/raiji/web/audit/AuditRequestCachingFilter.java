package com.raiji.web.audit;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

/**
 * Guarda o corpo de requisições JSON que mudam estado (limite de tamanho) para
 * que o interceptor consiga ler apenas campos de decisão, como ACCEPT/REJECT.
 *
 * <p>Não guarda uploads nem corpos grandes. O conteúdo não é persistido.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class AuditRequestCachingFilter extends OncePerRequestFilter {

    private static final long MAX_CACHE_BYTES = 128 * 1024;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!shouldWrap(request)) {
            chain.doFilter(request, response);
            return;
        }
        chain.doFilter(new ContentCachingRequestWrapper(request), response);
    }

    private boolean shouldWrap(HttpServletRequest request) {
        String method = request.getMethod();
        if ("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)) {
            return false;
        }
        String contentType = request.getContentType();
        if (contentType != null && contentType.toLowerCase().startsWith("multipart/")) {
            return false;
        }
        long length = request.getContentLengthLong();
        return length < 0 || length <= MAX_CACHE_BYTES;
    }
}
