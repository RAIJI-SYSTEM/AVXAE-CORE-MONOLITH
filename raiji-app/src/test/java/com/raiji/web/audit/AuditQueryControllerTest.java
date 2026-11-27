package com.raiji.web.audit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.raiji.rides.domain.AuditLog;
import com.raiji.rides.repository.AuditLogRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditQueryControllerTest {

    @Mock
    private AuditLogRepository repository;

    @Test
    void mineReturnsOwnEventsLimited() {
        AuditLog entry = entry("sub-1");
        when(repository.findTop200ByActorSubOrderByCreatedAtDesc("sub-1"))
                .thenReturn(List.of(entry));

        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("sub-1").build();
        List<AuditLog> result = new AuditQueryController(repository).mine(jwt, 1);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getActorSub()).isEqualTo("sub-1");
    }

    @Test
    void adminSearchClampsLimitAndAcceptsFilters() {
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(entry("sub-2"))));

        List<AuditLog> result = new AuditQueryController(repository)
                .search("sub-2", "RIDE_CREATED", Instant.now().minusSeconds(60),
                        Instant.now(), UUID.randomUUID(), 0);

        assertThat(result).hasSize(1);
    }

    private AuditLog entry(String actor) {
        AuditLog log = new AuditLog();
        log.setAction("RIDE_CREATED");
        log.setEntityType("RIDE");
        log.setActorSub(actor);
        log.setCreatedAt(Instant.now());
        return log;
    }
}
