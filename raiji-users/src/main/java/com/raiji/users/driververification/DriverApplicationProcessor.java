package com.raiji.users.driververification;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Processa a verificação depois que o pedido foi gravado (AFTER_COMMIT), numa
 * thread à parte: a consulta à Infosimples pode levar dezenas de segundos e a
 * requisição do app já respondeu 202. O app acompanha por GET /api/users/me/driver/cnh.
 */
@Component
public class DriverApplicationProcessor {

    private final DriverVerificationService service;

    public DriverApplicationProcessor(DriverVerificationService service) {
        this.service = service;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSubmitted(DriverApplicationSubmittedEvent event) {
        service.process(event.applicationId(), event.securityCode());
    }

    @Configuration
    @EnableAsync
    static class AsyncConfig {
    }
}
