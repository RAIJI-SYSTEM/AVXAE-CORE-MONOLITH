package com.raiji.rides.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.raiji.rides.domain.NotificationAudience;
import com.raiji.rides.dto.NotificationResponse;
import com.raiji.rides.service.NotificationService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Caixa de entrada do usuário logado, separada por modo do app:
 * GET  /api/notifications?audience=PASSENGER|DRIVER|COMPANY
 * GET  /api/notifications/unread-count?audience=...
 * POST /api/notifications/read  { "audience": "...", "ids": [...] }  (ids vazio = todas)
 */
@RestController
@RequestMapping("/api/notifications")
@PreAuthorize("isAuthenticated()")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public List<NotificationResponse> list(@AuthenticationPrincipal Jwt jwt,
                                           @RequestParam(defaultValue = "PASSENGER") NotificationAudience audience) {
        return notificationService.list(jwt.getSubject(), audience);
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unread(@AuthenticationPrincipal Jwt jwt,
                                    @RequestParam(defaultValue = "PASSENGER") NotificationAudience audience) {
        return Map.of("unread", notificationService.countUnread(jwt.getSubject(), audience));
    }

    public record MarkReadRequest(NotificationAudience audience, List<UUID> ids) {}

    @PostMapping("/read")
    public Map<String, Integer> markRead(@AuthenticationPrincipal Jwt jwt, @RequestBody MarkReadRequest body) {
        NotificationAudience audience = body.audience() == null ? NotificationAudience.PASSENGER : body.audience();
        return Map.of("updated", notificationService.markRead(jwt.getSubject(), audience, body.ids()));
    }
}
