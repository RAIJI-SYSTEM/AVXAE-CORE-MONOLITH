package com.raiji.deliveries.service;

import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;

/**
 * Códigos da encomenda:
 * - coleta: 4 dígitos que o remetente mostra ao motorista;
 * - entrega: palavra-chave que o destinatário informa ao motorista;
 * - rastreamento: código público para identificar a encomenda.
 */
public final class DeliveryCodes {

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final List<String> WORDS = List.of(
            "SOL", "LUA", "MAR", "RIO", "CEU", "FLOR", "MEL", "PAZ", "LUZ", "SAL",
            "COCO", "CAJU", "MANGA", "UVA", "PERA", "NUVEM", "ONDA", "PEDRA", "FOLHA", "VENTO",
            "IPE", "ARARA", "TATU", "BOTO", "SABIA", "CAQUI", "AREIA", "BRISA", "CORAL", "LAGO"
    );

    /**
     * Erros seguidos permitidos antes de bloquear a confirmação.
     */
    public static final int MAX_FAILED_ATTEMPTS = 5;

    private DeliveryCodes() {
    }

    /**
     * Código de coleta com quatro dígitos.
     */
    public static String pickupCode() {
        return String.format("%04d", RANDOM.nextInt(10_000));
    }

    /**
     * Palavra-chave de entrega.
     */
    public static String keyword() {
        return WORDS.get(RANDOM.nextInt(WORDS.size()))
                + "-"
                + String.format("%02d", RANDOM.nextInt(100));
    }

    /**
     * Código de rastreamento:
     * R + 19 caracteres hexadecimais = 20 caracteres.
     */
    public static String trackingCode() {
        return "R" + UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 19)
                .toUpperCase();
    }

    /**
     * Compara ignorando maiúsculas, espaços e hífen.
     */
    public static boolean matches(String expected, String typed) {
        if (expected == null || typed == null) {
            return false;
        }

        return normalize(expected).equals(normalize(typed));
    }

    private static String normalize(String s) {
        return s.toUpperCase().replaceAll("[^A-Z0-9]", "");
    }
}
