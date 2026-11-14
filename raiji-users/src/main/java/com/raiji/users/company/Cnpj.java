package com.raiji.users.company;

/** CNPJ: só dígitos (aceita "12.345.678/0001-95") e dígitos verificadores. */
public final class Cnpj {

    private Cnpj() {
    }

    public static String normalize(String raw) {
        return raw == null ? "" : raw.replaceAll("\\D", "");
    }

    public static boolean isValid(String digits) {
        if (digits == null || !digits.matches("\\d{14}") || digits.chars().distinct().count() == 1) {
            return false;
        }
        int[] w1 = {5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
        int[] w2 = {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
        return digit(digits, w1) == digits.charAt(12) - '0' && digit(digits, w2) == digits.charAt(13) - '0';
    }

    private static int digit(String d, int[] weights) {
        int sum = 0;
        for (int i = 0; i < weights.length; i++) {
            sum += (d.charAt(i) - '0') * weights[i];
        }
        int r = sum % 11;
        return r < 2 ? 0 : 11 - r;
    }

    /** 12345678000195 -> 12.345.678/0001-95 */
    public static String format(String d) {
        if (d == null || d.length() != 14) return d;
        return d.substring(0, 2) + "." + d.substring(2, 5) + "." + d.substring(5, 8) + "/"
                + d.substring(8, 12) + "-" + d.substring(12);
    }
}
