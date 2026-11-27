package com.raiji.web.audit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.raiji.rides.domain.AuditLog;
import com.raiji.rides.repository.AuditLogRepository;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.persistence.criteria.Predicate;

/**
 * Consulta da trilha de auditoria.
 *
 * <ul>
 *   <li>{@code GET /api/audit/me}: últimas ações do próprio usuário;</li>
 *   <li>{@code GET /api/admin/audit}: visão administrativa filtrável.</li>
 * </ul>
 */
@RestController
public class AuditQueryController {

    private static final int MAX_LIMIT = 500;

    private final AuditLogRepository repository;

    public AuditQueryController(AuditLogRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/api/audit/me")
    @PreAuthorize("isAuthenticated()")
    public List<AuditLog> mine(@AuthenticationPrincipal Jwt jwt,
                               @RequestParam(defaultValue = "100") int limit) {
        return repository.findTop200ByActorSubOrderByCreatedAtDesc(jwt.getSubject())
                .stream()
                .limit(clamp(limit))
                .toList();
    }

    @GetMapping("/api/admin/audit")
    @PreAuthorize("hasRole('admin')")
    public List<AuditLog> search(
            @RequestParam(required = false) String actorSub,
            @RequestParam(required = false) String action,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) UUID entityId,
            @RequestParam(defaultValue = "100") int limit) {

        Specification<AuditLog> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (actorSub != null && !actorSub.isBlank()) {
                predicates.add(cb.equal(root.get("actorSub"), actorSub));
            }
            if (action != null && !action.isBlank()) {
                predicates.add(cb.equal(root.get("action"), action));
            }
            if (from != null) predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            if (to != null) predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
            if (entityId != null) predicates.add(cb.equal(root.get("entityId"), entityId));
            return cb.and(predicates.toArray(Predicate[]::new));
        };

        return repository.findAll(spec,
                        PageRequest.of(0, clamp(limit), Sort.by(Sort.Direction.DESC, "createdAt")))
                .getContent();
    }

    private int clamp(int limit) {
        if (limit < 1) return 1;
        return Math.min(limit, MAX_LIMIT);
    }
}
