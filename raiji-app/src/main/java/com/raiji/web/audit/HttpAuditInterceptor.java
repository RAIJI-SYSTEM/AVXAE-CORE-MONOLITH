package com.raiji.web.audit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.ContentCachingRequestWrapper;

/**
 * Registra cada requisição em {@code /api/**}: ator, rota, status, duração e
 * resultado. Requisições autenticadas que mudam estado também vão para o
 * {@code audit_log}; leituras ficam apenas no log JSON.
 */
@Component
public class HttpAuditInterceptor implements HandlerInterceptor {

    private static final String START_ATTR = HttpAuditInterceptor.class.getName() + ".start";
    private static final String REQUEST_ID_ATTR = HttpAuditInterceptor.class.getName() + ".requestId";
    private static final String SERVICE = "avxae-core-monolith";
    private static final String SYSTEM_ACTOR = "system";

    private final AuditActionMapper mapper;
    private final AuditEventLogger eventLogger;
    private final AuditLogWriter logWriter;
    private final ObjectMapper json;

    public HttpAuditInterceptor(AuditActionMapper mapper,
                                AuditEventLogger eventLogger,
                                AuditLogWriter logWriter,
                                ObjectMapper json) {
        this.mapper = mapper;
        this.eventLogger = eventLogger;
        this.logWriter = logWriter;
        this.json = json;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(START_ATTR, System.nanoTime());
        String requestId = resolveRequestId(request);
        request.setAttribute(REQUEST_ID_ATTR, requestId);
        if (!response.containsHeader("X-Request-Id")) {
            response.setHeader("X-Request-Id", requestId);
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        if (request.isAsyncStarted()) return;

        String path = request.getRequestURI();
        if ("/error".equals(path)) return;

        long start = startNanos(request);
        long durationMs = start == 0 ? 0 : (System.nanoTime() - start) / 1_000_000;
        int status = response.getStatus();
        String outcome = outcome(status, ex);

        Authentication authentication = null;
        try {
            authentication = org.springframework.security.core.context.SecurityContextHolder
                    .getContext().getAuthentication();
        } catch (RuntimeException ignored) {
            // contexto indisponível: segue como anônimo
        }

        String actorSub = null;
        String actorUsername = null;
        List<String> roles = new ArrayList<>();
        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            actorSub = jwtAuth.getToken().getSubject();
            actorUsername = jwtAuth.getToken().getClaimAsString("preferred_username");
            if (actorUsername == null) actorUsername = jwtAuth.getName();
            for (GrantedAuthority authority : authentication.getAuthorities()) {
                roles.add(authority.getAuthority());
            }
        } else if (authentication != null && authentication.isAuthenticated()) {
            actorUsername = authentication.getName();
        }

        String decision = readDecision(request);
        AuditActionMapper.Target target = mapper.resolve(request.getMethod(), path, decision);
        String action = actionFor(target.action(), status);

        boolean systemCall = isSystemCall(path, request.getMethod());
        boolean authenticatedWrite = actorSub != null && !isReadOnly(request.getMethod());
        boolean persisted = systemCall || authenticatedWrite;
        if (systemCall) {
            actorSub = SYSTEM_ACTOR;
            actorUsername = SYSTEM_ACTOR;
        }

        AuditEvent event = new AuditEvent(
                SERVICE,
                "http_request",
                (String) request.getAttribute(REQUEST_ID_ATTR),
                request.getMethod(),
                path,
                null,
                status,
                durationMs,
                actorSub,
                actorUsername,
                roles,
                clientIp(request),
                request.getHeader("User-Agent"),
                action,
                target.entityType(),
                target.entityId(),
                decision,
                outcome,
                persisted);

        eventLogger.log(event);
        logWriter.write(event);
    }

    private String actionFor(String action, int status) {
        if (status < 400 || action.startsWith("HTTP_") || action.endsWith("_FAILED")) {
            return action;
        }
        String failed = action + "_FAILED";
        return failed.length() <= 50 ? failed : action;
    }

    private boolean isReadOnly(String method) {
        String m = method == null ? "" : method.toUpperCase(Locale.ROOT);
        return "GET".equals(m) || "HEAD".equals(m) || "OPTIONS".equals(m);
    }

    private boolean isSystemCall(String path, String method) {
        if (method == null || !"POST".equals(method.toUpperCase(Locale.ROOT))) return false;
        return "/api/public/stripe/webhook".equals(path)
                || path.startsWith("/api/public/rewards/admob");
    }

    private String outcome(int status, Exception ex) {
        if (ex != null || status >= 500) return "ERROR";
        if (status == 401 || status == 403) return "DENIED";
        if (status >= 400) return "FAILURE";
        return "SUCCESS";
    }

    private long startNanos(HttpServletRequest request) {
        Object value = request.getAttribute(START_ATTR);
        return value instanceof Long l ? l : 0L;
    }

    private String resolveRequestId(HttpServletRequest request) {
        String id = request.getHeader("X-Kong-Request-Id");
        if (id == null || id.isBlank()) id = request.getHeader("X-Request-Id");
        return id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return comma > 0 ? forwarded.substring(0, comma).trim() : forwarded.trim();
        }
        return request.getRemoteAddr();
    }

    /** Lê somente o campo "decision" (ACCEPT/REJECT) do corpo JSON já cacheado. */
    private String readDecision(HttpServletRequest request) {
        if (!(request instanceof ContentCachingRequestWrapper wrapper)) return null;
        byte[] content = wrapper.getContentAsByteArray();
        if (content.length == 0 || content.length > 64 * 1024) return null;
        try {
            JsonNode node = json.readTree(content);
            String decision = node.path("decision").asText(null);
            return decision == null || decision.isBlank() ? null : decision;
        } catch (Exception ex) {
            return null;
        }
    }
}
