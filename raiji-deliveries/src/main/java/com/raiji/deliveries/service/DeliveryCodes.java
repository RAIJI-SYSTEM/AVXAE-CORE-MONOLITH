package com.raiji.deliveries.service;

import java.security.SecureRandom;
import java.util.List;

/**
 * Códigos da encomenda:
 * <ul>
 *   <li>coleta: 4 dígitos que o REMETENTE mostra ao motorista quando entrega o pacote;</li>
 *   <li>entrega: palavra-chave (ex.: SOL-42) que o DESTINATÁRIO diz ao motorista.</li>
 * </ul>
 * O motorista não vê nenhum dos dois: ele digita o que recebe.
 */
public final class DeliveryCodes {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final List<String> WORDS = List.of(
            "SOL", "LUA", "MAR", "RIO", "CEU", "FLOR", "MEL", "PAZ", "LUZ", "SAL",
            "COCO", "CAJU", "MANGA", "UVA", "PERA", "NUVEM", "ONDA", "PEDRA", "FOLHA", "VENTO",
            "IPE", "ARARA", "TATU", "BOTO", "SABIA", "CAQUI", "AREIA", "BRISA", "CORAL", "LAGO");

    /** Erros seguidos permitidos antes de bloquear a confirmação (contra tentativa e erro). */
    public static final int MAX_FAILED_ATTEMPTS = 5;

    private DeliveryCodes() {
    }

    public static String pickupCode() {
        return String.format("%04d", RANDOM.nextInt(10_000));
    }

    public static String keyword() {
        return WORDS.get(RANDOM.nextInt(WORDS.size())) + "-" + String.format("%02d", RANDOM.nextInt(100));
    }

    /** Compara ignorando maiúsculas, espaços e o hífen ("sol 42" = "SOL-42"). */
    public static boolean matches(String expected, String typed) {
        if (expected == null || typed == null) return false;
        return normalize(expected).equals(normalize(typed));
    }

    private static String normalize(String s) {
        return s.toUpperCase().replaceAll("[^A-Z0-9]", "");
    }
}
