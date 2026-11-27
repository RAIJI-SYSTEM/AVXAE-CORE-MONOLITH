package com.raiji.web.audit;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.util.ContentCachingRequestWrapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class HttpAuditInterceptorTest {

    @Mock
    private AuditEventLogger eventLogger;
    @Mock
    private AuditLogWriter logWriter;

    private HttpAuditInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new HttpAuditInterceptor(
                new AuditActionMapper(), eventLogger, logWriter, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void auditsAuthenticatedStateChangeWithDecision() throws Exception {
        authenticate("driver-1", "maria", "ROLE_DRIVER");
        MockHttpServletRequest request = new MockHttpServletRequest(
                "PATCH", "/api/rides/11111111-1111-1111-1111-111111111111/requests/"
                + "22222222-2222-2222-2222-222222222222");
        request.setContentType("application/json");
        request.setContent("{\"decision\":\"REJECT\",\"reason\":\"lotado\"}".getBytes());
        request.addHeader("X-Kong-Request-Id", "req-123");
        request.addHeader("X-Forwarded-For", "203.0.113.9, 10.0.0.1");
        request.addHeader("User-Agent", "test-agent");

        ContentCachingRequestWrapper wrapper = new ContentCachingRequestWrapper(request);
        wrapper.getInputStream().readAllBytes();

        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        interceptor.preHandle(wrapper, response, new Object());
        interceptor.afterCompletion(wrapper, response, new Object(), null);

        AuditEvent event = captureEvent();
        assertThat(event.service()).isEqualTo("avxae-core-monolith");
        assertThat(event.actorSub()).isEqualTo("driver-1");
        assertThat(event.actorUsername()).isEqualTo("maria");
        assertThat(event.roles()).contains("ROLE_DRIVER");
        assertThat(event.action()).isEqualTo("BOOKING_REJECTED");
        assertThat(event.entityType()).isEqualTo("RIDE_REQUEST");
        assertThat(event.entityId()).hasToString("22222222-2222-2222-2222-222222222222");
        assertThat(event.decision()).isEqualTo("REJECT");
        assertThat(event.status()).isEqualTo(200);
        assertThat(event.outcome()).isEqualTo("SUCCESS");
        assertThat(event.persisted()).isTrue();
        assertThat(event.requestId()).isEqualTo("req-123");
        assertThat(event.clientIp()).isEqualTo("203.0.113.9");
        assertThat(event.userAgent()).isEqualTo("test-agent");
        assertThat(response.getHeader("X-Request-Id")).isEqualTo("req-123");
        verify(logWriter).write(event);
    }

    @Test
    void readsAreLoggedButNotPersisted() {
        authenticate("user-1", "ana", "ROLE_PASSENGER");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/rides/search");
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.preHandle(request, response, new Object());
        interceptor.afterCompletion(request, response, new Object(), null);

        AuditEvent event = captureEvent();
        assertThat(event.action()).isEqualTo("HTTP_GET");
        assertThat(event.persisted()).isFalse();
        assertThat(event.requestId()).isNotBlank();
    }

    @Test
    void anonymousRequestHasNoActor() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/public/anything");
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.preHandle(request, response, new Object());
        interceptor.afterCompletion(request, response, new Object(), null);

        AuditEvent event = captureEvent();
        assertThat(event.actorSub()).isNull();
        assertThat(event.persisted()).isFalse();
    }

    @Test
    void systemWebhookIsPersistedWithSystemActor() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/public/stripe/webhook");
        request.setContentType("application/json");
        request.setContent("{}".getBytes());
        request.setAttribute(HttpAuditInterceptor.class.getName() + ".start", System.nanoTime());
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.afterCompletion(request, response, new Object(), null);

        AuditEvent event = captureEvent();
        assertThat(event.actorSub()).isEqualTo("system");
        assertThat(event.action()).isEqualTo("PAYMENT_WEBHOOK");
        assertThat(event.persisted()).isTrue();
    }

    @Test
    void deniedAndErrorOutcomes() {
        authenticate("user-1", "ana", "ROLE_PASSENGER");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/rides");
        request.setContentType("application/json");
        request.setContent(new byte[0]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(403);

        interceptor.preHandle(request, response, new Object());
        interceptor.afterCompletion(request, response, new Object(), null);

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(eventLogger).log(captor.capture());
        assertThat(captor.getValue().outcome()).isEqualTo("DENIED");

        MockHttpServletResponse error = new MockHttpServletResponse();
        error.setStatus(500);
        interceptor.afterCompletion(request, error, new Object(), null);
        ArgumentCaptor<AuditEvent> all = ArgumentCaptor.forClass(AuditEvent.class);
        verify(eventLogger, org.mockito.Mockito.times(2)).log(all.capture());
        assertThat(all.getAllValues().get(1).outcome()).isEqualTo("ERROR");
    }

    @Test
    void invalidJsonDoesNotBreakAudit() {
        authenticate("user-1", "ana", "ROLE_PASSENGER");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/rides");
        request.setContentType("application/json");
        request.setContent("{invalido".getBytes());
        ContentCachingRequestWrapper wrapper = new ContentCachingRequestWrapper(request);
        try {
            wrapper.getInputStream().readAllBytes();
        } catch (Exception ignored) {
            // não deve acontecer
        }
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.preHandle(wrapper, response, new Object());
        interceptor.afterCompletion(wrapper, response, new Object(), null);

        assertThat(captureEvent().decision()).isNull();
    }

    @Test
    void asyncRequestIsIgnored() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/rides");
        request.setAsyncSupported(true);
        request.startAsync();
        interceptor.afterCompletion(request, new MockHttpServletResponse(), new Object(), null);
        verifyNoInteractions(eventLogger);
        verify(logWriter, never()).write(any());
    }

    private void authenticate(String sub, String username, String authority) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(sub)
                .claim("preferred_username", username)
                .claim("realm_access", Map.of("roles", List.of("DRIVER")))
                .build();
        var authentication = new JwtAuthenticationToken(
                jwt, List.of(new SimpleGrantedAuthority(authority)), username);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private AuditEvent captureEvent() {
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(eventLogger).log(captor.capture());
        return captor.getValue();
    }
}
