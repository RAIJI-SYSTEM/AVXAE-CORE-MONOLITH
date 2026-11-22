package com.raiji.deliveries.service;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class RecipientNotificationSimulator {

    private static final Logger log =
            LoggerFactory.getLogger(RecipientNotificationSimulator.class);

    public void sendConfirmationCode(
            UUID deliveryId,
            String recipientPhone,
            String confirmationCode
    ) {
        String maskedPhone = maskPhone(recipientPhone);

        log.info(
                "[NOTIFICAÇÃO SIMULADA] Entrega {} | Telefone: {} | Código: {}",
                deliveryId,
                maskedPhone,
                confirmationCode
        );
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 4) {
            return "telefone inválido";
        }

        return "*****" + phone.substring(phone.length() - 4);
    }
}
