package com.raiji.web.audit;

import java.util.concurrent.Executor;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;

class AuditConfigTest {

    @Test
    void createsBoundedExecutor() {
        Executor executor = new AuditConfig().auditExecutor();
        assertThat(executor).isInstanceOf(ThreadPoolTaskExecutor.class);
        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) executor;
        assertThat(pool.getCorePoolSize()).isEqualTo(1);
        assertThat(pool.getMaxPoolSize()).isEqualTo(2);
        pool.shutdown();
    }
}
