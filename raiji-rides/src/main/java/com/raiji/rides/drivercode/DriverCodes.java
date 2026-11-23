package com.raiji.rides.drivercode;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Código do motorista: 8 dígitos tirados da conta (sempre o mesmo para a mesma pessoa),
 * para o passageiro achar o motorista sem saber o nome (ex.: "4821-3759").
 */
public final class DriverCodes {

    public static final int LENGTH = 8;
    private static final long MODULUS = 100_000_000L;

    private DriverCodes() {
    }

    public static String of(String driverSub) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(driverSub.getBytes(StandardCharsets.UTF_8));
            long value = ByteBuffer.wrap(hash, 0, Long.BYTES).getLong() & Long.MAX_VALUE;
            return String.format("%0" + LENGTH + "d", value % MODULUS);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String normalize(String code) {
        return code == null ? "" : code.replaceAll("\\D", "");
    }
}
