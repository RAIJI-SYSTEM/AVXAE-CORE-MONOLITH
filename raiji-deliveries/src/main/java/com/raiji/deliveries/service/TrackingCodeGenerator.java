package com.raiji.deliveries.service;

import java.security.SecureRandom;

import com.raiji.deliveries.repository.DeliveryRepository;
import org.springframework.stereotype.Component;

@Component
public class TrackingCodeGenerator {

    private static final String ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private static final int MAX_ATTEMPTS = 100;

    private final SecureRandom random = new SecureRandom();
    private final DeliveryRepository deliveries;

    public TrackingCodeGenerator(DeliveryRepository deliveries) {
        this.deliveries = deliveries;
    }

    public String generate() {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String code = generateCandidate();

            if (!deliveries.existsByTrackingCode(code)) {
                return code;
            }
        }

        throw new IllegalStateException(
                "Não foi possível gerar um código de rastreamento único."
        );
    }

    private String generateCandidate() {
        return "RM-" + randomPart(6) + "-" + randomPart(2);
    }

    private String randomPart(int length) {
        StringBuilder result = new StringBuilder(length);

        for (int i = 0; i < length; i++) {
            result.append(ALPHABET.charAt(
                    random.nextInt(ALPHABET.length())
            ));
        }

        return result.toString();
    }
}