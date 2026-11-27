package com.raiji.web.audit;

import java.util.List;
import java.util.UUID;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

class AuditEventLoggerTest {

    @Test
    void writesJsonAuditLineWithActorAndAction() {
        Logger logger = (Logger) LoggerFactory.getLogger("AUDIT");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Level previous = logger.getLevel();
        logger.setLevel(Level.INFO);
        logger.addAppender(appender);

        try {
            new AuditEventLogger().log(new AuditEvent(
                    "avxae-core-monolith", "http_request", "req-1", "POST",
                    "/api/rides", null, 201, 12, "sub-1", "maria",
                    List.of("ROLE_DRIVER"), "127.0.0.1", "test",
                    "RIDE_CREATED", "HTTP", null, null, "SUCCESS", true));

            assertThat(appender.list).hasSize(1);
            ILoggingEvent event = appender.list.get(0);
            assertThat(event.getFormattedMessage()).isEqualTo("audit_http_request");
            assertThat(event.getMarker()).isNotNull();
            assertThat(event.getMarker().toString()).contains("actor_sub");
            assertThat(event.getMarker().toString()).contains("RIDE_CREATED");
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previous);
        }
    }

    @Test
    void omitsOptionalFieldsWhenAbsent() {
        Logger logger = (Logger) LoggerFactory.getLogger("AUDIT");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            new AuditEventLogger().log(new AuditEvent(
                    "avxae-core-monolith", "http_request", "req-2", "GET",
                    "/api/rides", "page=1", 200, 3, null, null,
                    List.of(), null, null, "HTTP_GET", "HTTP", UUID.randomUUID(),
                    null, "SUCCESS", false));
            assertThat(appender.list).hasSize(1);
        } finally {
            logger.detachAppender(appender);
        }
    }
}
